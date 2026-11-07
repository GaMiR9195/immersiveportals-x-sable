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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Portal transit of Sable sub-levels (README, sections 2-3). Runs at the TAIL of every
 * level's sub-level container tick, after physics, for the bodies of that level:
 *
 * <ol>
 *   <li>{@link IplPieceTracker} follows every aperture near every piece of the body: which
 *       material went through, which sessions exist;</li>
 *   <li>a body whose transit is complete flips through exactly one root session, together
 *       with its rigid mates, unless {@link IplAttachmentGate} says it is attached; flipped
 *       bodies are tracked again at once so rows and loops of portals continue in the same tick.</li>
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
        IplAttachmentGate.clearAll();
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
                List<Candidate> candidates = new ArrayList<>();
                for (ServerSubLevel ship : pending) {
                    if (ship.isRemoved()) continue;
                    if (round == 0 && transitTick % ACTOR_RESYNC_INTERVAL == 0) {
                        int rebound = IplTransitOps.resyncStaleActors(ship);
                        if (rebound > 0) {
                            LOG.debug("[IPL-TRANSIT] rebound {} stale physics actor(s) for {}", rebound, ship.getUniqueId());
                        }
                    }
                    IplPieceTracker.FlipChoice choice = IplPieceTracker.update(ship, tick);
                    if (choice != null) candidates.add(new Candidate(ship, choice));
                }
                pending = executeCandidates(server, candidates, movedRiders);
            }
            IplShipFlip.flushHandoffs(server);
        } finally {
            level.getProfiler().pop();
        }
    }

    private record Candidate(ServerSubLevel ship, IplPieceTracker.FlipChoice choice) {}

    /** Flips every ready body with its group; returns the live bodies to track again this tick. */
    private static List<ServerSubLevel> executeCandidates(MinecraftServer server, List<Candidate> candidates,
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
            TransitGroup group = transitGroup(ship);
            if (group == null) continue; // a failed joint walk is NOT a solo body
            List<IplPieceTracker.FlipChoice> mateChoices = new ArrayList<>(group.mates().size());
            boolean ready = IplShipFlip.canFlip(ship, portal);
            for (ServerSubLevel mate : group.mates()) {
                IplPieceTracker.FlipChoice mc = IplPieceTracker.pendingFlip(mate.getUniqueId());
                ready &= mc != null && !flipped.contains(mate.getUniqueId())
                    && sameTransit(portal, mc.portal()) && IplShipFlip.canFlip(mate, mc.portal());
                mateChoices.add(mc);
            }
            if (!ready || IplAttachmentGate.flipBlocker(ship, group.mates(), group.nativeJointed(),
                IplShipFlip.isCrossDimension(ship, portal)) != null) {
                continue; // the far side keeps living on the projection
            }
            long sourceScene = IplScenes.sceneHandle(ship.getLevel());
            int[] groupIds = new int[group.mates().size() + 1];
            groupIds[0] = Rapier3D.getID(ship);
            for (int i = 0; i < group.mates().size(); i++) groupIds[i + 1] = Rapier3D.getID(group.mates().get(i));
            boolean moveRopes = sourceScene != 0 && IplRapierNatives.abiLevel() >= IplRapierNatives.ROPE_TRANSIT_ABI
                && !IplShipFlip.isCrossDimension(ship, portal)
                && java.util.Arrays.stream(groupIds).anyMatch(id -> IplRapierNatives.ropesAttachedToSableBody(sourceScene, id).length > 0);
            ServerSubLevel live = flipBody(server, ship, c.choice(), movedRiders);
            if (live == null) continue;
            flipped.add(ship.getUniqueId());
            continuing.add(live);
            boolean groupMoved = true;
            for (int i = 0; i < group.mates().size(); i++) {
                ServerSubLevel mate = group.mates().get(i);
                if (mate.isRemoved() || !flipped.add(mate.getUniqueId())) { groupMoved = false; continue; }
                ServerSubLevel liveMate = flipBody(server, mate, mateChoices.get(i), movedRiders);
                if (liveMate == null) {
                    LOG.error("[IPL-TRANSIT] joint member {} declined after preflight", mate.getUniqueId());
                    groupMoved = false;
                    continue;
                }
                continuing.add(liveMate);
            }
            if (moveRopes && groupMoved) {
                var mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
                var shift = mapping.shift();
                var rotation = mapping.mapQuat(new org.joml.Quaterniond());
                if (!IplRapierNatives.moveGroupRopes(sourceScene, groupIds, shift.x, shift.y, shift.z,
                    rotation.x, rotation.y, rotation.z, rotation.w)) {
                    LOG.error("[IPL-TRANSIT] failed to reframe ropes after moving group {}", ship.getUniqueId());
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

    /** Members crossing together with a body (sorted by UUID) and whether native joints are involved. */
    private record TransitGroup(List<ServerSubLevel> mates, boolean nativeJointed) {}

    /**
     * All native-joint mates plus actor-linked partners (springs, connectors...)
     * of {@code body}, walked transitively. A flexible joint is still a connection:
     * its bodies must wait for one another before the native frame changes.
     */
    @Nullable
    private static TransitGroup transitGroup(ServerSubLevel body) {
        List<ServerSubLevel> rigid = jointGroupMates(body);
        if (rigid == null) return null;
        boolean nativeJointed = !rigid.isEmpty();
        LinkedHashMap<UUID, ServerSubLevel> members = new LinkedHashMap<>();
        members.put(body.getUniqueId(), body);
        for (ServerSubLevel mate : rigid) members.putIfAbsent(mate.getUniqueId(), mate);
        ArrayDeque<ServerSubLevel> queue = new ArrayDeque<>(members.values());
        while (!queue.isEmpty()) {
            ServerSubLevel member = queue.poll();
            for (ServerSubLevel partner : IplAttachmentGate.actorPartners(member)) {
                if (members.containsKey(partner.getUniqueId())) continue;
                List<ServerSubLevel> partnerRigid = jointGroupMates(partner);
                if (partnerRigid == null) return null;
                nativeJointed |= !partnerRigid.isEmpty();
                members.put(partner.getUniqueId(), partner);
                queue.add(partner);
                for (ServerSubLevel pm : partnerRigid) {
                    if (members.putIfAbsent(pm.getUniqueId(), pm) == null) queue.add(pm);
                }
            }
        }
        members.remove(body.getUniqueId());
        if (members.isEmpty()) return new TransitGroup(List.of(), nativeJointed);
        List<ServerSubLevel> mates = new ArrayList<>(members.values());
        mates.sort(Comparator.comparing(m -> m.getUniqueId().toString()));
        return new TransitGroup(mates, nativeJointed);
    }

    /** Bodies connected by native joints (they cross together); null if the walk failed. */
    @Nullable
    private static List<ServerSubLevel> jointGroupMates(ServerSubLevel body) {
        if (!IplRapierNatives.isAvailable()) return List.of();
        long scene = IplScenes.sceneHandle(body.getLevel());
        if (scene == 0) return null;
        int[] ids = IplRapierNatives.connectedSableBodyIds(scene, Rapier3D.getID(body), false);
        if (ids.length == 0) return null;
        int selfId = body.getRuntimeId();
        if (ids.length == 1) return ids[0] == selfId ? List.of() : null;
        var container = SubLevelContainer.getContainer(body.getLevel());
        if (container == null) return null;
        List<ServerSubLevel> mates = new ArrayList<>(ids.length - 1);
        for (int id : ids) {
            if (id == selfId) continue;
            for (var sub : container.getAllSubLevels()) {
                if (sub.getRuntimeId() == id && !sub.isRemoved() && sub instanceof ServerSubLevel server) {
                    mates.add(server);
                    break;
                }
            }
        }
        if (mates.size() != ids.length - 1) return null;
        mates.sort(Comparator.comparing(m -> m.getUniqueId().toString()));
        return mates;
    }

    /** Flipped faces of one doorway have distinct UUIDs but the same isometry. */
    private static boolean sameTransit(Portal a, Portal b) {
        return a.level() == b.level() && a.getDestDim().equals(b.getDestDim())
            && IplStraddlePoseMap.StraddleMapping.of(a).sameAs(IplStraddlePoseMap.StraddleMapping.of(b));
    }
}
