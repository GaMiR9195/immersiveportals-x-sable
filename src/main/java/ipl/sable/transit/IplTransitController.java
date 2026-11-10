package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.IplTimeline;
import ipl.sable.atlas.IplFusedStep;
import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Portal transit of Sable sub-levels (README, sections 2-3). Shared entry runs at solver
 * boundaries; retirement and handoff run at container tick tail, after physics:
 *
 * <ol>
 *   <li>{@link IplPieceTracker} observes each link's own material, while
 *       {@link IplGroupTransitContext} prepares and holds a shared path tree from the first
 *       link's entrance, also at every physics boundary;</li>
 *   <li>a body whose transit is complete flips through exactly one root session, together
 *       with the complete Sable connection chain; flipped bodies are tracked again at once so
 *       rows and loops of portals continue in the same tick.</li>
 * </ol>
 */
public final class IplTransitController {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    private static final int ACTOR_RESYNC_INTERVAL = 100;
    /** Flips per body per tick (portal loops at extreme speed); the rest continues next tick. */
    private static final int MAX_TRANSITS_PER_TICK = 16;

    private static int lastServerTick = Integer.MIN_VALUE;
    private static long transitTick;

    private IplTransitController() {}

    public static void onServerStopping(MinecraftServer server) {
        IplShipFlip.clearPending();
        IplGrabChain.clearAll();
        IplStraddleSessionSync.clearAll();
        IplAtlasStraddleSession.clearAll();
        IplPieceTracker.clearAll();
        IplShipPortalAnchor.clearAll();
        IplShipNetherPortal.clearPending();
        IplRopeTransit.clearAll();
        IplFusedStep.onServerStopping();
        IplTimeline.reset();
        lastServerTick = Integer.MIN_VALUE;
    }

    /** Once per server tick, at the first container tick of the tick: forget vanished bodies. */
    private static void beginServerTick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick == lastServerTick) return;
        lastServerTick = tick;
        transitTick++;
        IplPieceTracker.retainLive(server, id -> shipAlive(server, id));
        for (StraddleKey key : IplAtlasStraddleSession.sessionKeys()) {
            if (!IplAtlasStraddleSession.hasSessionKey(key)) continue;
            Portal portal = IplAtlasStraddleSession.sessionPortal(key);
            if (portal == null || portal.isRemoved() || !shipAlive(server, key.subLevelUuid())) {
                IplStraddleSessionSync.onSessionEnd(server, key, "stale");
                IplAtlasStraddleSession.clear(key, "stale");
            }
        }
    }

    private static boolean shipAlive(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(uuid) instanceof ServerSubLevel sub && !sub.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    public static void onContainerTick(ServerSubLevelContainer container) {
        ServerLevel level = container.getLevel();
        MinecraftServer server = level.getServer();
        level.getProfiler().push("ipl_sable_transit");
        try {
            beginServerTick(server);
            List<ServerSubLevel> pending = new ArrayList<>();
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (!ship.isRemoved()) pending.add(ship);
            }
            if (pending.isEmpty()) return;
            int tick = server.getTickCount();
            Map<UUID, UUID> movedRiders = new HashMap<>();
            for (int round = 0; round < MAX_TRANSITS_PER_TICK && !pending.isEmpty(); round++) {
                Map<UUID, ServerSubLevel> sampled = new LinkedHashMap<>();
                List<ServerSubLevel> roundPending = pending;
                boolean resync = round == 0 && transitTick % ACTOR_RESYNC_INTERVAL == 0;
                Map<UUID, IplTransitConnections.Group> groups = IplStraddleSessionSync.batchResult(() -> {
                    for (ServerSubLevel ship : roundPending) {
                        if (ship.isRemoved()) continue;
                        if (resync) {
                            int rebound = IplTransitOps.resyncStaleActors(ship);
                            if (rebound > 0) {
                                LOG.debug("[IPL-TRANSIT] rebound {} stale physics actor(s) for {}", rebound, ship.getUniqueId());
                            }
                        }
                        IplPieceTracker.update(ship, tick);
                        sampled.put(ship.getUniqueId(), ship);
                    }
                    return coordinateGroups(container, sampled, tick, true);
                });
                List<Candidate> candidates = new ArrayList<>();
                for (ServerSubLevel ship : sampled.values()) {
                    IplPieceTracker.publish(ship);
                    IplPieceTracker.FlipChoice choice = IplPieceTracker.pendingFlip(ship.getUniqueId());
                    if (choice != null) candidates.add(new Candidate(ship, choice));
                }
                pending = executeCandidates(server, candidates, groups, movedRiders);
            }
            IplShipFlip.flushHandoffs(server);
        } finally {
            level.getProfiler().pop();
        }
    }

    private record Candidate(ServerSubLevel ship, IplPieceTracker.FlipChoice choice) {}

    /** Every solver boundary: observe all links, join the context, then upload all native masks. */
    public static void onPhysicsSample(ServerSubLevelContainer container) {
        if (!IplRapierNatives.piecesSupported()) return;
        int tick = container.getLevel().getServer().getTickCount();
        IplStraddleSessionSync.batch(() -> {
            Map<UUID, ServerSubLevel> sampled = new LinkedHashMap<>();
            for (ServerSubLevel ship : liveBodies(container)) {
                IplPieceTracker.samplePhysics(ship);
                sampled.put(ship.getUniqueId(), ship);
            }
            coordinateGroups(container, sampled, tick, false);
            for (ServerSubLevel ship : sampled.values()) IplPieceTracker.publishPhysics(ship);
        });
        IplRopeTransit.sample(container.getLevel());
    }

    private static List<ServerSubLevel> liveBodies(ServerSubLevelContainer container) {
        List<ServerSubLevel> live = new ArrayList<>();
        for (ServerSubLevel ship : container.getAllSubLevels())
            if (!ship.isRemoved()) live.add(ship);
        return live;
    }

    /** Resolve the same Sable connection chains for entry, holding, and handoff. */
    private static Map<UUID, IplTransitConnections.Group> coordinateGroups(ServerSubLevelContainer container,
                                                    Map<UUID, ServerSubLevel> sampled, int tick, boolean finish) {
        Map<UUID, IplTransitConnections.Group> groups = new HashMap<>();
        // A continuation round may already own bodies in a new level after a cross-dimension
        // handoff. Looking only in the original container would lose its dependency chain.
        Set<ServerLevel> sources = new LinkedHashSet<>();
        sources.add(container.getLevel());
        for (ServerSubLevel ship : sampled.values()) if (!ship.isRemoved()) sources.add(ship.getLevel());
        for (ServerLevel source : sources) {
            var current = SubLevelContainer.getContainer(source);
            if (current == null) continue;
            for (var group : IplTransitConnections.activeGroups(source, liveBodies(current))) {
                for (ServerSubLevel member : group.members()) {
                    if (!sampled.containsKey(member.getUniqueId())) {
                        if (finish) IplPieceTracker.update(member, tick);
                        else IplPieceTracker.samplePhysics(member);
                        sampled.put(member.getUniqueId(), member);
                    }
                }
                if (!group.complete()) continue;
                boolean ready = finish ? IplSessionLifecycle.settle(group.members(), tick)
                    : IplGroupTransitContext.prepare(group.members(), tick) != null;
                if (ready)
                    for (ServerSubLevel member : group.members()) groups.put(member.getUniqueId(), group);
            }
        }
        return groups;
    }

    /** Flips every ready body with its group; returns the live bodies to track again this tick. */
    private static List<ServerSubLevel> executeCandidates(MinecraftServer server, List<Candidate> candidates,
                                                          Map<UUID, IplTransitConnections.Group> groups,
                                                          Map<UUID, UUID> movedRiders) {
        if (candidates.isEmpty()) return List.of();
        candidates.sort(Comparator
            .comparing((Candidate c) -> c.choice().portal().getUUID().toString())
            .thenComparing(c -> c.ship().getUniqueId().toString()));
        Set<UUID> flipped = new HashSet<>();
        List<ServerSubLevel> continuing = new ArrayList<>();
        for (Candidate c : candidates) {
            ServerSubLevel ship = c.ship();
            Portal portal = c.choice().portal();
            if (flipped.contains(ship.getUniqueId()) || ship.isRemoved()) continue;
            var group = groups.get(ship.getUniqueId());
            if (group == null || !group.complete()) continue;
            List<ServerSubLevel> mates = group.members().stream()
                .filter(member -> !member.getUniqueId().equals(ship.getUniqueId())).toList();
            List<IplPieceTracker.FlipChoice> mateChoices = new ArrayList<>(mates.size());
            boolean ready = IplShipFlip.canFlip(ship, portal);
            for (ServerSubLevel mate : mates) {
                IplPieceTracker.FlipChoice mc = IplPieceTracker.pendingFlip(mate.getUniqueId());
                ready &= mc != null && !flipped.contains(mate.getUniqueId())
                    && sameTransit(portal, mc.portal()) && IplShipFlip.canFlip(mate, mc.portal());
                mateChoices.add(mc);
            }
            if (!ready) {
                continue; // the far side keeps living on the projection
            }
            boolean crossDimension = IplShipFlip.isCrossDimension(ship, portal);
            int[] groupIds = new int[mates.size() + 1];
            groupIds[0] = Rapier3D.getID(ship);
            for (int i = 0; i < mates.size(); i++) groupIds[i + 1] = Rapier3D.getID(mates.get(i));

            Set<Long> groupRopes = new HashSet<>();
            long sourceScene = IplScenes.sceneHandle(ship.getLevel());
            if (sourceScene != 0 && IplRapierNatives.abiLevel() >= IplRapierNatives.ROPE_TRANSIT_ABI) {
                for (int id : groupIds) {
                    for (long packed : IplRapierNatives.ropesAttachedToSableBody(sourceScene, id)) {
                        groupRopes.add(packed >> 1);
                    }
                }
                if (crossDimension && !groupRopes.isEmpty()) {
                    // Rope strands are level-owned. Keep the connected sub-level chain on its
                    // projection rather than moving the bodies away from their rope particles.
                    continue;
                }
                if (!crossDimension && !IplRapierNatives.canMoveGroupRopes(sourceScene, groupIds)) {
                    // A same-dimension rope with an endpoint outside this Sable chain cannot
                    // be reframed safely. Leave the whole chain on the projection.
                    continue;
                }
            }
            boolean moveRopes = !groupRopes.isEmpty();
            ServerSubLevel live = flipBody(server, ship, c.choice(), movedRiders);
            if (live == null) continue;
            flipped.add(ship.getUniqueId());
            continuing.add(live);
            boolean groupMoved = true;
            for (int i = 0; i < mates.size(); i++) {
                ServerSubLevel mate = mates.get(i);
                if (mate.isRemoved() || !flipped.add(mate.getUniqueId())) { groupMoved = false; continue; }
                ServerSubLevel liveMate = flipBody(server, mate, mateChoices.get(i), movedRiders);
                if (liveMate == null) {
                    LOG.error("[IPL-TRANSIT] joint member {} declined after preflight", mate.getUniqueId());
                    groupMoved = false;
                    continue;
                }
                continuing.add(liveMate);
            }
            if (groupMoved && sourceScene != 0
                && !crossDimension
                && IplRapierNatives.abiLevel() >= IplRapierNatives.JOINT_TRANSIT_ABI
                && !IplRapierNatives.resetGroupJointImpulses(sourceScene, groupIds)) {
                LOG.error("[IPL-TRANSIT] failed to reset joint warm starts after moving group {}", ship.getUniqueId());
            }
            if (moveRopes && groupMoved) {
                var mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
                var shift = mapping.shift();
                var rotation = mapping.mapQuat(new org.joml.Quaterniond());
                if (!IplRapierNatives.moveGroupRopes(sourceScene, groupIds, shift.x, shift.y, shift.z,
                    rotation.x, rotation.y, rotation.z, rotation.w)) {
                    LOG.error("[IPL-TRANSIT] failed to reframe ropes after moving group {}", ship.getUniqueId());
                } else {
                    IplRopeTransit.onRopesMoved(live.getLevel(), groupRopes, portal);
                }
            }
        }
        return continuing;
    }

    /** One body through its chosen root session; its sessions below that portal are promoted. */
    @Nullable
    private static ServerSubLevel flipBody(MinecraftServer server, ServerSubLevel ship,
                                           IplPieceTracker.FlipChoice choice, Map<UUID, UUID> movedRiders) {
        List<IplAtlasStraddleSession.Promotion> promotions = IplAtlasStraddleSession.promotionsThrough(choice.key());
        ChunkPos oldMin = ship.getPlot().getChunkMin();
        ServerSubLevel live = IplPieceTracker.duringFlip(() -> IplShipFlip.flip(ship, choice.portal(), movedRiders));
        if (live == null) return null;
        List<StraddleKey> opened = IplAtlasStraddleSession.promote(live, promotions);
        IplPieceTracker.afterFlip(live, choice.node(), oldMin, promotions, opened);
        for (StraddleKey key : opened) {
            Portal portal = IplAtlasStraddleSession.sessionPortal(key);
            if (portal != null) IplStraddleSessionSync.onSessionStart(server, live, key, portal);
        }
        IplGrabChain.onBodyTransit(server, live.getUniqueId(), choice.portal());
        return live;
    }

    /** Flipped faces of one doorway have distinct UUIDs but the same isometry. */
    private static boolean sameTransit(Portal a, Portal b) {
        return a.level() == b.level() && a.getDestDim().equals(b.getDestDim())
            && IplStraddlePoseMap.StraddleMapping.of(a).sameAs(IplStraddlePoseMap.StraddleMapping.of(b));
    }
}
