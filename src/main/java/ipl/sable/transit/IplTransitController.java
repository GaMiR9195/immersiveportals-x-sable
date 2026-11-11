package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.IplPerfDiagnostics;
import ipl.sable.IplTimeline;
import ipl.sable.atlas.IplFusedStep;
import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Portal transit of Sable sub-levels (README, sections 2-3).
 *
 * <ol>
 *   <li>At every solver boundary {@link IplPieceTracker} observes each body's own material and
 *       opens a session where the body touches an aperture; rope points follow the frames of
 *       the bodies they hang on ({@link IplRopeRouting}).</li>
 *   <li>At the container tick tail each body is settled on its own
 *       ({@link IplSessionLifecycle}) and its labels are published.</li>
 *   <li>A body whose crossing is complete flips with its whole linked group ({@link IplLinks})
 *       once every member with blocks has completed the same crossing; until then the passed
 *       members keep living on their projections. Flipped bodies are tracked again at once, so
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
        IplRopeRouting.clearAll();
        IplLinks.clearAll();
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

    /** Every solver boundary: observe each body, upload its native masks, route rope points. */
    public static void onPhysicsSample(ServerSubLevelContainer container) {
        if (!IplRapierNatives.piecesSupported()) return;
        long start = IplPerfDiagnostics.ENABLED ? System.nanoTime() : 0L;
        IplStraddleSessionSync.batch(() -> {
            for (ServerSubLevel ship : liveBodies(container)) {
                IplPieceTracker.samplePhysics(ship);
                IplPieceTracker.publishPhysics(ship);
            }
        });
        IplRopeRouting.sample(container.getLevel());
        if (IplPerfDiagnostics.ENABLED)
            IplPerfDiagnostics.record(IplPerfDiagnostics.SERVER_SAMPLE, System.nanoTime() - start);
    }

    /** Container tick tail: settle, publish and flip. */
    public static void onContainerTick(ServerSubLevelContainer container) {
        ServerLevel level = container.getLevel();
        MinecraftServer server = level.getServer();
        level.getProfiler().push("ipl_sable_transit");
        try {
            beginServerTick(server);
            List<ServerSubLevel> pending = liveBodies(container);
            int tick = server.getTickCount();
            Map<UUID, UUID> movedRiders = new HashMap<>();
            for (int round = 0; round < MAX_TRANSITS_PER_TICK && !pending.isEmpty(); round++) {
                List<ServerSubLevel> bodies = pending;
                boolean resync = round == 0 && transitTick % ACTOR_RESYNC_INTERVAL == 0;
                IplStraddleSessionSync.batch(() -> {
                    for (ServerSubLevel ship : bodies) {
                        if (ship.isRemoved()) continue;
                        if (resync) resyncActors(ship);
                        IplPieceTracker.update(ship, tick);
                        IplSessionLifecycle.settle(ship, tick);
                    }
                });
                List<Candidate> candidates = new ArrayList<>();
                for (ServerSubLevel ship : bodies) {
                    if (ship.isRemoved()) continue;
                    IplPieceTracker.publish(ship);
                    IplPieceTracker.FlipChoice choice = IplPieceTracker.pendingFlip(ship.getUniqueId());
                    if (choice != null) candidates.add(new Candidate(ship, choice));
                }
                pending = flipReady(server, candidates, movedRiders);
            }
            IplShipFlip.flushHandoffs(server);
        } finally {
            level.getProfiler().pop();
        }
    }

    private static void resyncActors(ServerSubLevel ship) {
        int rebound = IplTransitOps.resyncStaleActors(ship);
        if (rebound > 0) LOG.debug("[IPL-TRANSIT] rebound {} stale physics actor(s) for {}", rebound, ship.getUniqueId());
    }

    private static List<ServerSubLevel> liveBodies(ServerSubLevelContainer container) {
        List<ServerSubLevel> live = new ArrayList<>();
        for (ServerSubLevel ship : container.getAllSubLevels()) if (!ship.isRemoved()) live.add(ship);
        return live;
    }

    // ------------------------------------------------------------------ group flips

    private record Candidate(ServerSubLevel ship, IplPieceTracker.FlipChoice choice) {}

    /** One member of a crossing; {@code choice == null} for a zero-block connector that follows. */
    private record Step(ServerSubLevel ship, @Nullable IplPieceTracker.FlipChoice choice) {}

    private record Crossing(Portal portal, List<Step> steps, boolean crossDimension, Set<Long> ropes) {}

    /** Flips every group whose crossing is complete; returns the live bodies to track again this tick. */
    private static List<ServerSubLevel> flipReady(MinecraftServer server, List<Candidate> candidates,
                                                  Map<UUID, UUID> movedRiders) {
        if (candidates.isEmpty()) return List.of();
        candidates.sort(Comparator
            .comparing((Candidate c) -> c.choice().portal().getUUID().toString())
            .thenComparing(c -> c.ship().getUniqueId().toString()));
        Set<UUID> flipped = new HashSet<>();
        List<ServerSubLevel> continuing = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (flipped.contains(candidate.ship().getUniqueId()) || candidate.ship().isRemoved()) continue;
            Crossing crossing = plan(candidate, flipped);
            if (crossing == null) continue; // the far side keeps living on its projection
            continuing.addAll(cross(server, crossing, flipped, movedRiders));
        }
        return continuing;
    }

    /**
     * The crossing of {@code lead}'s group, or null while it must wait: a member with blocks
     * has not completed the same crossing, the group is anchored to the world or not fully
     * resolved, or a rope cannot follow it.
     */
    @Nullable
    private static Crossing plan(Candidate lead, Set<UUID> flipped) {
        Portal portal = lead.choice().portal();
        IplLinks.Group group = IplLinks.of(lead.ship());
        if (!group.complete() || group.anchored()) return null;
        List<Step> steps = new ArrayList<>(group.members().size());
        steps.add(new Step(lead.ship(), lead.choice()));
        for (ServerSubLevel member : group.members()) {
            if (member.getUniqueId().equals(lead.ship().getUniqueId())) continue;
            if (member.isRemoved() || flipped.contains(member.getUniqueId())) return null;
            IplPieceTracker.FlipChoice choice = IplPieceTracker.pendingFlip(member.getUniqueId());
            if (choice == null && IplPieceTracker.hasBlocks(member)) return null;
            Portal through = choice == null ? portal : choice.portal();
            if (!sameTransit(portal, through) || !IplShipFlip.canFlip(member, through)) return null;
            steps.add(new Step(member, choice));
        }
        if (!IplShipFlip.canFlip(lead.ship(), portal)) return null;
        boolean crossDimension = IplShipFlip.isCrossDimension(lead.ship(), portal);
        Set<Long> ropes = ropesOf(lead.ship().getLevel(), steps);
        if (!ropes.isEmpty()) {
            // Rope strands belong to their level; a rope with an end outside the group cannot follow.
            long scene = IplScenes.sceneHandle(lead.ship().getLevel());
            if (crossDimension || !IplRapierNatives.canMoveGroupRopes(scene, bodyIds(steps))) return null;
        }
        return new Crossing(portal, steps, crossDimension, ropes);
    }

    private static List<ServerSubLevel> cross(MinecraftServer server, Crossing crossing, Set<UUID> flipped,
                                              Map<UUID, UUID> movedRiders) {
        ServerLevel source = crossing.steps().getFirst().ship().getLevel();
        long scene = IplScenes.sceneHandle(source);
        int[] ids = bodyIds(crossing.steps());
        List<ServerSubLevel> moved = new ArrayList<>(crossing.steps().size());
        for (Step step : crossing.steps()) {
            ServerSubLevel ship = step.ship();
            if (ship.isRemoved() || !flipped.add(ship.getUniqueId())) continue;
            ServerSubLevel live = step.choice() == null
                ? follow(server, ship, crossing.portal(), movedRiders)
                : flipBody(server, ship, step.choice(), movedRiders);
            if (live == null) {
                LOG.error("[IPL-TRANSIT] {} declined its group's flip after preflight", ship.getUniqueId());
                continue;
            }
            moved.add(live);
        }
        IplLinks.invalidate();
        boolean whole = moved.size() == crossing.steps().size();
        if (!whole || crossing.crossDimension() || scene == 0) return moved;
        if (ids.length > 1 && IplRapierNatives.abiLevel() >= IplRapierNatives.JOINT_TRANSIT_ABI
            && !IplRapierNatives.resetGroupJointImpulses(scene, ids)) {
            LOG.error("[IPL-TRANSIT] failed to reset joint warm starts after moving group {}", ids[0]);
        }
        if (!crossing.ropes().isEmpty()) moveRopes(source, scene, ids, crossing);
        return moved;
    }

    private static void moveRopes(ServerLevel level, long scene, int[] ids, Crossing crossing) {
        var mapping = IplStraddlePoseMap.StraddleMapping.of(crossing.portal());
        var shift = mapping.shift();
        var rotation = mapping.mapQuat(new Quaterniond());
        if (IplRapierNatives.moveGroupRopes(scene, ids, shift.x, shift.y, shift.z,
            rotation.x, rotation.y, rotation.z, rotation.w)) {
            IplRopeRouting.forget(level, crossing.ropes());
        } else {
            LOG.error("[IPL-TRANSIT] failed to move the ropes of group {}", ids[0]);
        }
    }

    private static Set<Long> ropesOf(ServerLevel level, List<Step> steps) {
        long scene = IplScenes.sceneHandle(level);
        if (scene == 0 || IplRapierNatives.abiLevel() < IplRapierNatives.ROPE_TRANSIT_ABI) return Set.of();
        Set<Long> ropes = new HashSet<>();
        for (int id : bodyIds(steps))
            for (long packed : IplRapierNatives.ropesAttachedToSableBody(scene, id)) ropes.add(packed >> 1);
        return ropes;
    }

    private static int[] bodyIds(List<Step> steps) {
        int[] ids = new int[steps.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = Rapier3D.getID(steps.get(i).ship());
        return ids;
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

    /** A zero-block connector has no material history: it simply goes with its group. */
    @Nullable
    private static ServerSubLevel follow(MinecraftServer server, ServerSubLevel ship, Portal portal,
                                         Map<UUID, UUID> movedRiders) {
        ServerSubLevel live = IplPieceTracker.duringFlip(() -> IplShipFlip.flip(ship, portal, movedRiders));
        if (live != null) IplGrabChain.onBodyTransit(server, live.getUniqueId(), portal);
        return live;
    }

    /** Flipped faces of one doorway have distinct UUIDs but the same isometry. */
    private static boolean sameTransit(Portal a, Portal b) {
        return a.level() == b.level() && a.getDestDim().equals(b.getDestDim())
            && IplStraddlePoseMap.StraddleMapping.of(a).sameAs(IplStraddlePoseMap.StraddleMapping.of(b));
    }
}
