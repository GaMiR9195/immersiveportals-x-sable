package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import ipl.sable.atlas.IplScenes;
import ipl.sable.mixin.LevelChunkSectionAccessor;
import ipl.sable.natives.IplRapierNatives;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Material history of every body near a portal. Native-backed bodies are sampled before the
 * first physics substep and after every pose readback; the tick tail settles each body
 * ({@link IplSessionLifecycle}), publishes labels and reference geometry and offers completed
 * flips to {@link IplTransitController}. Stock natives sample at the tick tail only.
 *
 * <p>Every body owns its own sessions, labels and sweep history. Joints never open a session
 * on a body that does not touch an aperture: linked bodies are coupled only at the flip
 * ({@link IplLinks}).
 *
 * <p>Finite-aperture events are applied in observed time order, including sibling faces and
 * newly reached nested frames. An empty aperture is never a timeout.
 */
public final class IplPieceTracker {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-piece-tracker");

    /** Portal search margin around the bounds of a piece image. */
    private static final double PORTAL_QUERY_INFLATION = 4.0;
    /** Half diagonal of a voxel: conservative lateral broad-phase margin. */
    private static final double VOXEL_RADIUS = 0.8660254037844386;
    /** Lateral slack of the bridge test: a voxel overlapping the rectangle edge still bridges. */
    private static final double BRIDGE_SLACK = 0.5;

    private static final Map<UUID, Body> BODIES = new HashMap<>();
    /** Set while a flip copies or moves blocks: those block events are not placements. */
    private static boolean flipping;
    private static boolean loggedNoNatives;
    private static boolean loggedNodeCap;

    private IplPieceTracker() {}

    /** The flip a body is ready for: through the root session {@code key}, piece {@code node}. */
    public record FlipChoice(StraddleKey key, Portal portal, int node) {}

    private record WatchKey(int node, UUID portal) {}

    /** One (piece, portal) pair followed across ticks. */
    private static final class Watch {
        Portal portal;
        /** Aperture in the plot frame at the last update. */
        @Nullable IplPieceMap.Aperture plot;
        double zone;
        /** Material still lies (partly) on the entry side of the plane (last sweep). */
        boolean cut;
        int seenTick = Integer.MIN_VALUE;
        long sample = Long.MIN_VALUE;
        @Nullable IplPieceMap.Aperture sampleBefore;
        @Nullable IplPieceMap.Aperture world;
        @Nullable IplPieceMap.Aperture motionWorldBefore;
        long motion = Long.MIN_VALUE;

        boolean cutAt(int tick) {
            return seenTick == tick && cut;
        }
    }

    static final class Body {
        final UUID id;
        ServerSubLevel owner;
        /** Last OBSERVED physics sample, never a predicted pose. */
        @Nullable Pose3d sampledPose;
        final IplPieceMap map = new IplPieceMap();
        /** Parent piece of each session piece; -1 = free. Piece 0 (native) is always live. */
        final int[] parent = new int[IplPieceMap.MAX_PIECE + 1];
        final StraddleKey[] keys = new StraddleKey[IplPieceMap.MAX_PIECE + 1];
        final Map<WatchKey, Watch> watches = new HashMap<>();
        /** Non-air blocks per label of every labelled section, as (label, count) pairs. */
        final Long2ObjectOpenHashMap<int[]> counts = new Long2ObjectOpenHashMap<>();
        final int[] totals = new int[IplPieceMap.MAX_PIECE + 1];
        final LongOpenHashSet recount = new LongOpenHashSet();
        final LongOpenHashSet unsynced = new LongOpenHashSet();
        final LongOpenHashSet unpushed = new LongOpenHashSet();
        boolean fullSync = true;
        boolean fullPush = true;
        /** Edges of the last label payload: an unchanged body sends nothing. */
        @Nullable IplPieceMap.Edge[] syncedEdges;
        /** Where the natives hold this body's piece data ({@code nativeHasPieces}), to clear it on a change of body or scene. */
        @Nullable ServerLevel pushedLevel;
        long pushedScene;
        int pushedBody = -1;
        boolean nativeHasPieces;
        @Nullable FlipChoice pendingFlip;
        /** Latest observed crossing time per voxel in this physics sample. */
        final Long2DoubleOpenHashMap movedAt = new Long2DoubleOpenHashMap();
        long sample;
        long motion;
        final Map<Integer, IplStraddlePoseMap.StraddleMapping> sampledMappings = new HashMap<>();

        Body(ServerSubLevel ship) {
            this.id = ship.getUniqueId();
            this.owner = ship;
            Arrays.fill(parent, -1);
        }

        boolean hasSessions() {
            for (int i = 1; i <= IplPieceMap.MAX_PIECE; i++) if (keys[i] != null) return true;
            return false;
        }

        boolean inSubtree(int node, int root) {
            if (root == IplPieceMap.NATIVE) return true;
            for (int n = node; n > 0; n = parent[n]) if (n == root) return true;
            return false;
        }

        int subtreeMaterial(int root) {
            int sum = 0;
            for (int n = 0; n <= IplPieceMap.MAX_PIECE; n++) {
                if (totals[n] != 0 && inSubtree(n, root)) sum += totals[n];
            }
            return sum;
        }

        @Nullable
        Watch edgeWatch(int node) {
            return keys[node] == null ? null : watches.get(new WatchKey(parent[node], keys[node].portalUuid()));
        }

        int childOf(int node, UUID portal) {
            for (int n = 1; n <= IplPieceMap.MAX_PIECE; n++) {
                if (keys[n] != null && parent[n] == node && keys[n].portalUuid().equals(portal)) return n;
            }
            return -1;
        }

        /** Live pieces, parents before children (session order). */
        List<Integer> liveNodes() {
            List<Integer> out = new ArrayList<>(4);
            out.add(IplPieceMap.NATIVE);
            for (StraddleKey key : IplAtlasStraddleSession.sessionKeysFor(id)) {
                int node = IplAtlasStraddleSession.sessionNode(key);
                if (node > 0 && key.equals(keys[node])) out.add(node);
            }
            return out;
        }

        int freeNode() {
            for (int n = 1; n <= IplPieceMap.MAX_PIECE; n++) if (keys[n] == null) return n;
            return -1;
        }
    }

    // ------------------------------------------------------------------ queries

    /** Server label map of a body with pieces, or null (everything native). */
    @Nullable
    public static IplPieceMap mapOf(UUID ship) {
        Body body = BODIES.get(ship);
        return body == null ? null : body.map;
    }

    /** Full label payload for a viewer that starts tracking {@code ship}, or null. */
    @Nullable
    public static CompoundTag fullLabels(UUID ship) {
        Body body = BODIES.get(ship);
        return body == null ? null : body.map.encode(null);
    }

    /** The flip computed after sampling and, when clear, group-wide settlement. */
    @Nullable
    static FlipChoice pendingFlip(UUID ship) {
        Body body = BODIES.get(ship);
        return body == null ? null : body.pendingFlip;
    }

    // ------------------------------------------------------------------ per tick

    /** Sample after physics. Publication is a separate phase, after all group members agree. */
    @Nullable
    static FlipChoice update(ServerSubLevel ship, int tick) {
        Body body = BODIES.get(ship.getUniqueId());
        if (body == null) {
            if (portalsNear(ship.getLevel(), queryBox(ship, ship.lastPose(), ship.logicalPose())).isEmpty()) return null;
            if (!IplRapierNatives.piecesSupported() && !loggedNoNatives) {
                loggedNoNatives = true;
                LOG.warn("[IPL-PIECES] natives without piece support (ABI {} < {}): the far side of a "
                    + "sub-level in a portal does not collide; rebuild the natives (logged once)",
                    IplRapierNatives.abiLevel(), IplRapierNatives.PIECE_ABI);
            }
            body = new Body(ship);
            BODIES.put(body.id, body);
        }
        sample(body, ship, tick);
        if (!body.hasSessions() && !body.map.isEmpty()) body.map.clear();
        settleCounts(body, ship);
        body.pendingFlip = flipChoice(body, ship, tick);
        return body.pendingFlip;
    }

    @Nullable
    static Body tracked(ServerSubLevel ship) {
        return BODIES.get(ship.getUniqueId());
    }

    /** Only final labels of this round are advertised, never its temporary canonicalization states. */
    static void publish(ServerSubLevel ship) {
        Body body = tracked(ship);
        if (body == null) return;
        settleCounts(body, ship);
        pushEdges(body, ship);
        pushLabels(body, ship);
        if (!body.hasSessions() && body.map.isEmpty() && body.watches.isEmpty()) BODIES.remove(body.id);
    }

    /** After a settlement moved material between frames: sweep again from the current pose. */
    static void settled(Body body, ServerSubLevel ship, int tick) {
        body.owner = ship;
        body.sampledPose = new Pose3d(ship.logicalPose());
        seedCurrentFrames(body, ship, tick);
        repaired(body, ship, tick);
    }

    /** Every retained/promoted frame needs contact geometry BEFORE another sample or tick. */
    private static void seedCurrentFrames(Body body, ServerSubLevel ship, int tick) {
        body.watches.clear();
        body.sampledMappings.clear();
        for (int node : body.liveNodes()) {
            if (node == IplPieceMap.NATIVE) continue;
            seedCurrentFrame(body, ship, node, tick);
        }
    }

    private static void seedCurrentFrame(Body body, ServerSubLevel ship, int node, int tick) {
        Portal portal = IplAtlasStraddleSession.sessionPortal(body.keys[node]);
        var mapping = IplAtlasStraddleSession.sessionMapping(body.keys[node]);
        if (portal == null || mapping == null) return;
        body.sampledMappings.putIfAbsent(node, mapping);
        WatchKey key = new WatchKey(body.parent[node], portal.getUUID());
        Watch previous = body.watches.get(key);
        if (previous != null && previous.plot != null) return;
        var parentMapping = body.parent[node] == IplPieceMap.NATIVE ? null
            : IplAtlasStraddleSession.sessionMapping(body.keys[body.parent[node]]);
        Pose3dc pose = parentMapping == null ? ship.logicalPose() : parentMapping.mapPose(ship.logicalPose());
        Watch watch = new Watch();
        watch.portal = portal;
        watch.world = IplPieceMap.Aperture.of(portal);
        watch.plot = watch.world.toPlot(pose);
        watch.zone = watch.plot.voxelReach() + 0.05;
        watch.motionWorldBefore = watch.world;
        watch.motion = body.motion;
        watch.seenTick = tick;
        body.watches.put(key, watch);
    }

    static void repaired(Body body, ServerSubLevel ship, int tick) {
        settleCounts(body, ship);
        body.pendingFlip = flipChoice(body, ship, tick);
    }

    /** Raw observation only; the controller prepares the whole group before uploading edges. */
    static void samplePhysics(ServerSubLevel ship) {
        if (ship.isRemoved() || !IplRapierNatives.piecesSupported()) return;
        Body body = BODIES.get(ship.getUniqueId());
        if (body == null) {
            if (portalsNear(ship.getLevel(), queryBox(ship, ship.lastPose(), ship.logicalPose())).isEmpty()) return;
            body = new Body(ship);
            BODIES.put(body.id, body);
        }
        sample(body, ship, ship.getLevel().getServer().getTickCount());
    }

    static void publishPhysics(ServerSubLevel ship) {
        Body body = tracked(ship);
        if (body != null) pushEdges(body, ship);
    }

    private static void sample(Body body, ServerSubLevel ship, int tick) {
        body.pendingFlip = null;
        rebindObservation(body, ship);
        IplAtlasStraddleSession.refreshShip(ship);
        reconcile(body, ship);
        Pose3d now = new Pose3d(ship.logicalPose());
        Pose3dc previous = body.sampledPose == null ? ship.lastPose() : body.sampledPose;
        double angle = 2.0*Math.acos(Math.min(1.0, Math.abs(previous.orientation().dot(now.orientation()))));
        for (var entry : body.watches.entrySet()) {
            Watch watch = entry.getValue();
            if (watch.plot == null || watch.portal == null || watch.portal.isRemoved()) continue;
            int parent = entry.getKey().node();
            var mapping = parent == IplPieceMap.NATIVE ? null
                : IplAtlasStraddleSession.sessionMapping(body.keys[parent]);
            Pose3dc pose = mapping == null ? now : mapping.mapPose(now);
            angle = Math.max(angle, IplApertureSweep.rotationAngle(watch.plot,
                IplPieceMap.Aperture.of(watch.portal).toPlot(pose)));
        }
        double radius = 1.0;
        BoundingBox3ic bounds = ship.getPlot().getBoundingBox();
        for (int i = 0; i < 8; i++) {
            var corner = new org.joml.Vector3d((i&1)==0 ? bounds.minX() : bounds.maxX()+1.0,
                (i&2)==0 ? bounds.minY() : bounds.maxY()+1.0, (i&4)==0 ? bounds.minZ() : bounds.maxZ()+1.0);
            radius = Math.max(radius, corner.distance(now.rotationPoint()));
        }
        int slices = IplTransitSettlement.angularSlices(angle, radius);
        Pose3d start = new Pose3d(previous);
        body.motion++;
        for (int i = 1; i <= slices; i++) {
            double fraction = (double)i/slices;
            Pose3d observed = i == slices ? now : start.lerp(now, fraction, new Pose3d());
            // Companion's pose lerp uses nlerp. Use equal angular fractions for the observed arc.
            if (i != slices) observed.orientation().set(start.orientation()).slerp(now.orientation(), fraction);
            sweepAll(body, ship, tick, observed, fraction, (double)(i-1)/slices);
            body.sampledPose = observed;
        }
        body.sampledMappings.clear();
        for (int node : body.liveNodes()) {
            if (node == IplPieceMap.NATIVE) continue;
            var mapping = IplAtlasStraddleSession.sessionMapping(body.keys[node]);
            if (mapping != null) body.sampledMappings.put(node, mapping);
        }
        settleCounts(body, ship);
    }

    private static void rebindObservation(Body body, ServerSubLevel ship) {
        if (body.owner != ship || body.pushedBody >= 0 && (body.pushedBody != Rapier3D.getID(ship)
            || body.pushedLevel != ship.getLevel() || body.pushedScene != IplScenes.sceneHandle(ship.getLevel()))) {
            // UUID survives an unload/replacement; a pose or image from the previous native
            // body must not become the starting point of a sweep in this body's frame.
            clearNativePieces(body);
            body.owner = ship;
            body.sampledPose = new Pose3d(ship.logicalPose());
            body.sampledMappings.clear();
            body.watches.clear();
            body.fullSync = body.fullPush = true;
        }
    }

    /** Pieces whose session vanished (portal removed, frame invalidated) fall back to their nearest live ancestor. */
    private static void reconcile(Body body, ServerSubLevel ship) {
        int[] target = null;
        for (int n = 1; n <= IplPieceMap.MAX_PIECE; n++) {
            if (body.keys[n] == null || IplAtlasStraddleSession.hasSessionKey(body.keys[n])) continue;
            if (target == null) {
                target = new int[IplPieceMap.MAX_PIECE + 1];
                for (int i = 0; i < target.length; i++) target[i] = i;
            }
            int up = body.parent[n];
            while (up > 0 && (body.keys[up] == null || !IplAtlasStraddleSession.hasSessionKey(body.keys[up]))) {
                up = body.parent[up];
            }
            target[n] = Math.max(up, IplPieceMap.NATIVE);
        }
        if (target != null) {
            int[] f = target;
            body.map.relabel(label -> f[label]);
            for (int n = 1; n <= IplPieceMap.MAX_PIECE; n++) {
                if (f[n] != n) {
                    // clear() can invalidate an entire subtree. The native session
                    // registry and the client advertisement are separate stores;
                    // clearing only the former leaves invisible/stale client pieces.
                    IplStraddleSessionSync.onSessionEnd(
                        ship.getLevel().getServer(), body.keys[n], "frame-invalidated");
                    free(body, n);
                }
            }
        }
        // Sessions opened elsewhere (promotions) that this body does not know yet.
        for (StraddleKey key : IplAtlasStraddleSession.sessionKeysFor(ship.getUniqueId())) {
            int node = IplAtlasStraddleSession.sessionNode(key);
            if (node <= 0 || key.equals(body.keys[node])) continue;
            body.keys[node] = key;
            body.parent[node] = key.isRoot() ? IplPieceMap.NATIVE : IplAtlasStraddleSession.sessionNode(key.parent());
        }
    }

    private static void free(Body body, int node) {
        body.keys[node] = null;
        body.parent[node] = -1;
        body.sampledMappings.remove(node);
        // Watches are keyed by a numeric parent id. Once that id is reused by a DIFFERENT
        // path, its old children must not sweep from the discarded parent's offset.
        body.watches.keySet().removeIf(key -> key.node() == node);
    }

    // ------------------------------------------------------------------ apertures

    private static void sweepAll(Body body, ServerSubLevel ship, int tick, Pose3dc observed, double fraction, double previousFraction) {
        // A newly entered piece may already intersect another portal this tick.
        // Visit newly opened nodes too; otherwise the first movement of the child
        // is lost and a nearby second aperture can be skipped permanently.
        body.movedAt.clear();
        body.sample++;
        long revision;
        do {
            revision = body.map.revision();
            List<Integer> nodes = new ArrayList<>(body.liveNodes());
            for (int at = 0; at < nodes.size(); at++) {
                int node = nodes.get(at);
                StraddleKey key = node == IplPieceMap.NATIVE ? null : body.keys[node];
                ServerLevel level = key == null ? ship.getLevel() : IplAtlasStraddleSession.sessionDest(key);
                IplStraddlePoseMap.StraddleMapping mapping = key == null ? null : IplAtlasStraddleSession.sessionMapping(key);
                Portal entered = key == null ? null : IplAtlasStraddleSession.sessionPortal(key);
                if (key != null && (level == null || mapping == null || entered == null)) continue;
                var currentMapping = mapping == null ? null : IplStraddlePoseMap.StraddleMapping.interpolate(
                    body.sampledMappings.get(node), mapping, fraction);
                var previousMapping = mapping == null ? null : IplStraddlePoseMap.StraddleMapping.interpolate(
                    body.sampledMappings.get(node), mapping, previousFraction);
                Pose3d pose = currentMapping == null ? new Pose3d(observed) : currentMapping.mapPose(observed);
                Pose3dc last = body.sampledPose == null ? ship.lastPose() : body.sampledPose;
                Pose3d previous = previousMapping == null ? new Pose3d(last) : previousMapping.mapPose(last);
                List<Portal> candidates = portalsNear(level, queryBox(ship, previous, pose));
                for (int n = 1; n <= IplPieceMap.MAX_PIECE; n++) {
                    if (body.keys[n] == null || body.parent[n] != node) continue;
                    Portal own = IplAtlasStraddleSession.sessionPortal(body.keys[n]);
                    if (own != null && !candidates.contains(own)) candidates.add(own);
                }
                candidates.sort(Comparator.comparing(portal -> portal.getUUID().toString()));
                List<AperturePlan> plans = new ArrayList<>();
                for (Portal portal : candidates) {
                    if (!IplAtlasStraddleSession.supports(level, portal) || IplShipPortalAnchor.isAnchorShip(portal, body.id)) continue;
                    if (entered != null && isExitFace(entered, portal)) continue;
                    int parentNode = node;
                    if (!isCanonicalFace(portal, candidates, face -> body.childOf(parentNode, face.getUUID()) > 0)) continue;
                    // Both faces of a bi-faced doorway are honest entrances (the U-shaped body that
                    // leaves through the front while its other arm enters through the back): edges
                    // only cut and hold their own material (the label-time material rule), so the two
                    // sibling sessions never compete for the voxels at the shared plane.
                    plans.add(planAperture(body, ship, node, portal, pose, previous, tick, fraction, previousFraction));
                }
                applyCrossings(body, ship, plans);
                // Sessions opened during this pass can themselves enter another aperture.
                for (int live : body.liveNodes()) if (!nodes.contains(live)) nodes.add(live);
            }
            // A return from a nested frame can enable a later crossing in an already visited
            // parent. Revisit the observed sample until stable; crossing times strictly increase.
        } while (body.map.revision() != revision);
        // Unused watches can retire; non-empty sessions keep their material independently.
        body.watches.values().removeIf(w -> w.seenTick != tick);
    }

    private record AperturePlan(int parent, Portal portal, Watch watch,
                                IplPieceMap.Aperture before, IplPieceMap.Aperture now, Sweep sweep) {}
    private record Crossing(double time, long voxel, AperturePlan plan, boolean forward) {}

    private static AperturePlan planAperture(Body body, ServerSubLevel ship, int node, Portal portal,
                                           Pose3dc pose, Pose3dc previous, int tick, double fraction, double previousFraction) {
        Watch watch = body.watches.computeIfAbsent(new WatchKey(node, portal.getUUID()), k -> new Watch());
        IplPieceMap.Aperture actualWorld = IplPieceMap.Aperture.of(portal);
        if (watch.motion != body.motion) {
            watch.motionWorldBefore = watch.world == null ? actualWorld : watch.world;
            watch.world = actualWorld;
            watch.motion = body.motion;
        }
        IplPieceMap.Aperture world = IplApertureSweep.interpolate(watch.motionWorldBefore, actualWorld, fraction);
        IplPieceMap.Aperture beforeWorld = IplApertureSweep.interpolate(watch.motionWorldBefore, actualWorld, previousFraction);
        IplPieceMap.Aperture now = world.toPlot(pose);
        if (watch.sample != body.sample) {
            watch.sampleBefore = watch.plot != null && watch.seenTick >= tick - 1 ? watch.plot : beforeWorld.toPlot(previous);
            watch.sample = body.sample;
        }
        IplPieceMap.Aperture before = watch.sampleBefore;
        int child = body.childOf(node, portal.getUUID());
        Sweep sweep = sweep(body, ship.getPlot(), before, now, now.voxelReach() + 0.05, node, child);
        watch.portal = portal;
        watch.plot = now;
        watch.zone = now.voxelReach() + 0.05;
        watch.seenTick = tick;
        return new AperturePlan(node, portal, watch, before, now, sweep);
    }

    /** Apply sibling events by crossing fraction, independent of portal UUID/session order. */
    private static void applyCrossings(Body body, ServerSubLevel ship, List<AperturePlan> plans) {
        List<Crossing> events = new ArrayList<>();
        for (AperturePlan plan : plans) {
            for (long voxel : plan.sweep.forward) events.add(new Crossing(crossingTime(plan, voxel), voxel, plan, true));
            for (long voxel : plan.sweep.backward) events.add(new Crossing(crossingTime(plan, voxel), voxel, plan, false));
        }
        events.sort(Comparator.comparingDouble(Crossing::time)
            .thenComparing(e -> e.plan.portal.getUUID().toString()));
        for (Crossing event : events) {
            if (event.time <= body.movedAt.getOrDefault(event.voxel, -1.0) + 1.0e-9) continue;
            AperturePlan plan = event.plan;
            int child = body.childOf(plan.parent, plan.portal.getUUID());
            int from = event.forward ? plan.parent : child;
            if (from < 0 || body.map.label(BlockPos.getX(event.voxel), BlockPos.getY(event.voxel),
                BlockPos.getZ(event.voxel)) != from) continue;
            if (event.forward && child < 0) child = openSession(body, ship, plan.parent, plan.portal);
            if (child < 0) continue;
            setLabel(body, event.voxel, event.forward ? child : plan.parent);
            body.movedAt.put(event.voxel, event.time);
        }
        for (AperturePlan plan : plans) {
            int child = body.childOf(plan.parent, plan.portal.getUUID());
            Sweep current = sweep(body, ship.getPlot(), plan.now, plan.now,
                plan.watch.zone, plan.parent, child);
            // Geometric pre-arming, not a velocity forecast: the voxel already touches the aperture.
            if (child < 0 && current.bridging) child = openSession(body, ship, plan.parent, plan.portal);
            plan.watch.cut = current.cut;
        }
    }

    private static double crossingTime(AperturePlan plan, long voxel) {
        double x = BlockPos.getX(voxel) + 0.5, y = BlockPos.getY(voxel) + 0.5, z = BlockPos.getZ(voxel) + 0.5;
        double a = plan.before.side(x, y, z), b = plan.now.side(x, y, z);
        return a / (a - b);
    }

    private static void setLabel(Body body, long packed, int label) {
        body.map.setLabel(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed), label);
    }

    static int openSession(Body body, ServerSubLevel ship, int parentNode, Portal portal) {
        int node = body.freeNode();
        if (node < 0) {
            if (!loggedNodeCap) {
                loggedNodeCap = true;
                LOG.warn("[IPL-PIECES] body {} has {} pieces; further portals are ignored (logged once)",
                    body.id, IplPieceMap.MAX_PIECE);
            }
            return -1;
        }
        StraddleKey key = parentNode == IplPieceMap.NATIVE
            ? new StraddleKey(body.id, portal.getUUID())
            : body.keys[parentNode].child(portal.getUUID());
        if (!IplAtlasStraddleSession.open(key, ship, portal, node)) return -1;
        body.keys[node] = key;
        body.parent[node] = parentNode;
        body.fullSync = true; // even an empty context image needs its label-time reference edge
        try {
            IplStraddleSessionSync.onSessionStart(ship.getLevel().getServer(), ship, key, portal);
            return node;
        } catch (RuntimeException | LinkageError failure) {
            // No material can own the node until this method returns. Roll back its image
            // and any partial advertisement, without touching the already existing paths.
            try {
                IplStraddleSessionSync.onSessionEnd(ship.getLevel().getServer(), key, "open-failed");
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                IplAtlasStraddleSession.clear(key, "open-failed");
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                free(body, node);
            }
            LOG.warn("[IPL-PIECES] session publication failed for ship {} path={}", body.id, key.path(), failure);
            return -1;
        }
    }

    /** What one aperture did to the material of one piece during the last tick. */
    private static final class Sweep {
        final LongArrayList forward = new LongArrayList();
        final LongArrayList backward = new LongArrayList();
        /**
         * Material of the piece or the session's piece overlaps the plane inside the rectangle:
         * the two pieces are still one body through this aperture.
         */
        boolean bridging;
        /** Some of that material still lies (partly) on the entry side of the plane. */
        boolean cut;
    }

    private static Sweep sweep(Body body, LevelPlot plot, IplPieceMap.Aperture before, IplPieceMap.Aperture now,
                               double zone, int node, int child) {
        Sweep out = new Sweep();
        BoundingBox3ic bounds = plot.getBoundingBox();
        double reach = now.voxelReach();
        double near = zone + reach;
        double r = near + VOXEL_RADIUS;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (IplPieceMap.Aperture a : new IplPieceMap.Aperture[]{before, now}) {
            for (int i = 0; i < 4; i++) {
                double[] c = a.corner(i);
                minX = Math.min(minX, c[0]);
                minY = Math.min(minY, c[1]);
                minZ = Math.min(minZ, c[2]);
                maxX = Math.max(maxX, c[0]);
                maxY = Math.max(maxY, c[1]);
                maxZ = Math.max(maxZ, c[2]);
            }
        }
        int x0 = Math.max(bounds.minX(), (int) Math.floor(minX - r));
        int y0 = Math.max(bounds.minY(), (int) Math.floor(minY - r));
        int z0 = Math.max(bounds.minZ(), (int) Math.floor(minZ - r));
        int x1 = Math.min(bounds.maxX(), (int) Math.floor(maxX + r));
        int y1 = Math.min(bounds.maxY(), (int) Math.floor(maxY + r));
        int z1 = Math.min(bounds.maxZ(), (int) Math.floor(maxZ + r));
        if (x0 > x1 || y0 > y1 || z0 > z1) return out;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    LevelChunkSection section = sectionAt(plot, sx, sy, sz);
                    if (section == null || section.hasOnlyAir()) continue;
                    byte[] labels = body.map.section(SectionPos.asLong(sx, sy, sz));
                    if (!IplApertureSweep.boxMayIntersect(before, now, sx << 4, sy << 4, sz << 4, 16.0, r)) continue;
                    sweepSection(out, section, labels, before, now, zone, near, reach, node, child,
                        Math.max(x0, sx << 4), Math.max(y0, sy << 4), Math.max(z0, sz << 4),
                        Math.min(x1, (sx << 4) + 15), Math.min(y1, (sy << 4) + 15), Math.min(z1, (sz << 4) + 15));
                }
            }
        }
        return out;
    }

    private static void sweepSection(Sweep out, LevelChunkSection section, @Nullable byte[] labels,
                                     IplPieceMap.Aperture before, IplPieceMap.Aperture now, double zone,
                                     double near, double reach, int node, int child,
                                     int x0, int y0, int z0, int x1, int y1, int z1) {
        double r = near + VOXEL_RADIUS;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    int label = labels == null ? IplPieceMap.NATIVE : labels[IplPieceMap.index(x, y, z)] & 0xFF;
                    double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
                    double s1 = now.side(cx, cy, cz);
                    double s0 = before.side(cx, cy, cz);
                    if (Math.min(s0, s1) > r || Math.max(s0, s1) < -r) continue;
                    if (section.getBlockState(x & 15, y & 15, z & 15).isAir()) continue;
                    boolean touching = Math.abs(s1) <= near && now.laterallyInside(cx, cy, cz, VOXEL_RADIUS);
                    if (s0 >= 0.0 && s1 < 0.0
                        && !Double.isNaN(IplApertureSweep.crossing(before, now, cx, cy, cz, 0.0)))
                        out.forward.add(BlockPos.asLong(x, y, z));
                    if (child > 0 && s0 < 0.0 && s1 >= 0.0
                        && !Double.isNaN(IplApertureSweep.crossing(before, now, cx, cy, cz, BRIDGE_SLACK)))
                        out.backward.add(BlockPos.asLong(x, y, z));
                    if (label != node && label != child) continue;
                    boolean onChildSide = label == child;
                    // Only material of this edge holds it and cuts the flip: a parent voxel on the
                    // entry side or a child voxel on the far side. Material that reached the other
                    // side without passing this rectangle (around the frame, through the opposite
                    // face of the doorway, another portal's projection) is not this edge's: it must
                    // neither keep the session alive nor be cut by its plane (the label-time material rule).
                    boolean consistent = onChildSide ? s1 < 0.0 : s1 >= 0.0;
                    if (consistent) {
                        if (touching && s1 > -(reach + 0.05)) out.cut = true;
                        if (Math.abs(s1) <= reach + 0.05 && now.laterallyInside(cx, cy, cz, BRIDGE_SLACK)) out.bridging = true;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ material

    /** Recounts the sections whose labels or blocks changed and queues them for natives and clients. */
    private static void settleCounts(Body body, ServerSubLevel ship) {
        for (long key : body.map.drainDirty()) {
            body.recount.add(key);
            body.unsynced.add(key);
            body.unpushed.add(key);
        }
        if (body.recount.isEmpty()) return;
        LevelPlot plot = ship.getPlot();
        for (long key : body.recount) recount(body, plot, key);
        body.recount.clear();
    }

    private static void recount(Body body, LevelPlot plot, long key) {
        int[] old = body.counts.remove(key);
        if (old != null) for (int i = 0; i < old.length; i += 2) body.totals[old[i]] -= old[i + 1];
        byte[] labels = body.map.section(key);
        if (labels == null) return;
        LevelChunkSection section = sectionAt(plot, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
        if (section == null || section.hasOnlyAir()) return;
        int[] tally = new int[IplPieceMap.MAX_PIECE + 1];
        int distinct = 0;
        for (int i = 0; i < IplPieceMap.SECTION_VOLUME; i++) {
            if (section.getBlockState(i & 15, (i >> 4) & 15, (i >> 8) & 15).isAir()) continue;
            if (tally[labels[i] & 0xFF]++ == 0) distinct++;
        }
        int[] pairs = new int[distinct * 2];
        int at = 0;
        for (int label = 0; label < tally.length; label++) {
            if (tally[label] == 0) continue;
            pairs[at++] = label;
            pairs[at++] = tally[label];
            body.totals[label] += tally[label];
        }
        body.counts.put(key, pairs);
    }

    /** Whether the body has any block at all; a zero-block connector follows its group. */
    static boolean hasBlocks(ServerSubLevel ship) {
        for (PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            for (LevelChunkSection section : chunk.getSections()) if (!section.hasOnlyAir()) return true;
        }
        return false;
    }

    /** Whether the native piece holds any block (labelled native, or in a section without labels). */
    private static boolean nativeHasMaterial(Body body, LevelPlot plot) {
        if (body.totals[IplPieceMap.NATIVE] > 0) return true;
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            ChunkPos pos = chunk.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i].hasOnlyAir()) continue;
                long key = SectionPos.asLong(pos.x, chunk.getSectionYFromSectionIndex(i), pos.z);
                if (body.map.section(key) == null) return true;
            }
        }
        return false;
    }

    /** Blocks of the native piece: labelled native, plus every block of a section without labels. */
    private static int nativeMaterial(Body body, LevelPlot plot) {
        int sum = body.totals[IplPieceMap.NATIVE];
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            ChunkPos pos = chunk.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i].hasOnlyAir()) continue;
                long key = SectionPos.asLong(pos.x, chunk.getSectionYFromSectionIndex(i), pos.z);
                if (body.map.section(key) == null) {
                    sum += ((LevelChunkSectionAccessor) sections[i]).ipl$getNonEmptyBlockCount();
                }
            }
        }
        return sum;
    }

    /** Ends session {@code node} with its chained sessions; their labels return to the parent piece. */
    static void endSubtree(Body body, MinecraftServer server, int node, String reason) {
        int up = body.parent[node];
        StraddleKey key = body.keys[node];
        int[] f = new int[IplPieceMap.MAX_PIECE + 1];
        for (int n = 0; n < f.length; n++) f[n] = n != IplPieceMap.NATIVE && body.inSubtree(n, node) ? up : n;
        body.map.relabel(label -> f[label]);
        for (int n = 1; n < f.length; n++) {
            if (f[n] != n && body.keys[n] != null) {
                IplStraddleSessionSync.onSessionEnd(server, body.keys[n], reason);
                free(body, n);
            }
        }
        if (key != null) IplAtlasStraddleSession.clear(key, reason);
        body.fullSync = true;
    }

    // ------------------------------------------------------------------ natives and clients

    /** Edges of every live session: plot frame for Java, the parent piece's world frame for the natives. */
    private static void pushEdges(Body body, ServerSubLevel ship) {
        List<IplPieceMap.Edge> edges = new ArrayList<>(2);
        List<double[]> world = new ArrayList<>(2);
        for (int node = 1; node <= IplPieceMap.MAX_PIECE; node++) {
            StraddleKey key = body.keys[node];
            if (key == null) continue;
            Watch watch = body.watches.get(new WatchKey(body.parent[node], key.portalUuid()));
            if (watch == null || watch.plot == null || watch.portal == null) continue;
            // Server edges are the apertures the labels were just made against: no consistency slack.
            edges.add(new IplPieceMap.Edge(body.parent[node], node, watch.plot, watch.plot));
            IplPieceMap.Aperture a = IplPieceMap.Aperture.of(watch.portal);
            IplPieceMap.Aperture p = watch.plot;
            // The ABI-5 plane prefix is followed by the full label-time rectangle (ABI 6).
            // Solver-time centre crossings use this observed geometry, never a motion slab.
            world.add(new double[]{body.parent[node], node, a.ox(), a.oy(), a.oz(), a.nx(), a.ny(), a.nz(),
                a.wx(), a.wy(), a.wz(), a.halfW(), a.hx(), a.hy(), a.hz(), a.halfH(), watch.zone, 0.0,
                p.ox(), p.oy(), p.oz(), p.nx(), p.ny(), p.nz(),
                p.wx(), p.wy(), p.wz(), p.halfW(), p.hx(), p.hy(), p.hz(), p.halfH()});
        }
        body.map.setEdges(edges.toArray(new IplPieceMap.Edge[0]));
        long scene = IplScenes.sceneHandle(ship.getLevel());
        int bodyId = Rapier3D.getID(ship);
        if (scene == 0 || bodyId < 0 || !IplRapierNatives.piecesSupported()) return;
        if (scene != body.pushedScene || bodyId != body.pushedBody) {
            // The body changed id or scene (move to another dimension, re-added body): the old
            // entry must not keep clipping contacts with labels nobody updates any more.
            clearNativePieces(body);
            body.pushedLevel = ship.getLevel();
            body.pushedScene = scene;
            body.pushedBody = bodyId;
            body.fullPush = true;
        }
        if (!body.hasSessions() && body.map.isEmpty()) {
            clearNativePieces(body);
            body.unpushed.clear();
            body.fullPush = false;
            return;
        }
        if (body.fullPush) {
            body.unpushed.addAll(body.map.sectionKeys());
            body.fullPush = false;
        }
        for (long key : body.unpushed) {
            IplRapierNatives.setPieceSection(scene, bodyId, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key),
                body.map.section(key));
        }
        body.unpushed.clear();
        int stride = IplRapierNatives.nativeEdgeStride();
        double[] packed = new double[world.size() * stride];
        for (int i = 0; i < world.size(); i++) {
            System.arraycopy(world.get(i), 0, packed, i * stride, stride);
        }
        IplRapierNatives.setPieceEdges(scene, bodyId, packed);
        body.nativeHasPieces = true;
    }

    private static boolean sameEdges(IplPieceMap.Edge[] a, @Nullable IplPieceMap.Edge[] b) {
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].parent != b[i].parent || a[i].child != b[i].child
                || !a[i].aperture.equals(b[i].aperture) || !a[i].reference.equals(b[i].reference)) return false;
        }
        return true;
    }

    /** Drops the piece data the natives hold for this body, if its scene is still alive. */
    private static void clearNativePieces(Body body) {
        if (body.nativeHasPieces && body.pushedLevel != null
            && IplScenes.sceneHandle(body.pushedLevel) == body.pushedScene) {
            IplRapierNatives.clearPieces(body.pushedScene, body.pushedBody);
        }
        body.nativeHasPieces = false;
    }

    private static void pushLabels(Body body, ServerSubLevel ship) {
        IplPieceMap.Edge[] edges = body.map.edges();
        if (!body.fullSync && body.unsynced.isEmpty() && sameEdges(edges, body.syncedEdges)) return;
        CompoundTag payload = body.map.encode(body.fullSync ? null : body.unsynced.toLongArray());
        body.fullSync = false;
        body.unsynced.clear();
        body.syncedEdges = edges;
        IplStraddleSessionSync.sendLabels(ship.getLevel().getServer(), body.id, payload);
    }

    // ------------------------------------------------------------------ flips

    /**
     * Ready when the native piece holds nothing and exactly one root owns all material.
     * Different material roots cannot silently follow an arbitrary largest root: they first
     * need a geometrically clear, group-wide settlement.
     */
    @Nullable
    private static FlipChoice flipChoice(Body body, ServerSubLevel ship, int tick) {
        if (!body.hasSessions() || nativeHasMaterial(body, ship.getPlot())) return null;
        int best = -1;
        int bestMaterial = 0;
        int materialRoots = 0;
        for (int node : body.liveNodes()) {
            if (node == IplPieceMap.NATIVE || body.parent[node] != IplPieceMap.NATIVE) continue;
            int material = body.subtreeMaterial(node);
            if (material > 0) materialRoots++;
            if (material > bestMaterial) {
                best = node;
                bestMaterial = material;
            }
        }
        if (best < 0 || materialRoots != 1) return null;
        Watch watch = body.edgeWatch(best);
        if (watch != null && watch.cutAt(tick)) return null;
        Portal portal = IplAtlasStraddleSession.sessionPortal(body.keys[best]);
        return portal == null ? null : new FlipChoice(body.keys[best], portal, best);
    }

    /** Runs a flip with block events muted (the move copies the plot; those are not placements). */
    static <T> T duringFlip(Supplier<T> flip) {
        boolean outer = flipping;
        flipping = true;
        try {
            return flip.get();
        } finally {
            flipping = outer;
        }
    }

    /**
     * Re-roots the labels after {@code live} flipped through the session of piece {@code node}:
     * that piece becomes native, the sessions below it keep their pieces (promoted), and every
     * other piece (a leftover of the old native piece, other root sessions) joins the new
     * native piece. {@code oldMin} is the plot origin before the flip.
     */
    static void afterFlip(ServerSubLevel live, int node, ChunkPos oldMin,
                          List<IplAtlasStraddleSession.Promotion> promotions, List<StraddleKey> opened) {
        Body body = BODIES.get(live.getUniqueId());
        if (body == null) return;
        int[] f = new int[IplPieceMap.MAX_PIECE + 1];
        Map<String, Integer> byPath = new HashMap<>();
        for (IplAtlasStraddleSession.Promotion p : promotions) {
            String path = p.via().isEmpty() ? p.portal().getUUID().toString() : p.via() + ">" + p.portal().getUUID();
            int up = p.via().isEmpty() ? IplPieceMap.NATIVE : byPath.getOrDefault(p.via(), IplPieceMap.NATIVE);
            boolean reopened = false;
            for (StraddleKey key : opened) {
                if (key.path().equals(path) && IplAtlasStraddleSession.sessionNode(key) == p.node()) reopened = true;
            }
            f[p.node()] = reopened ? p.node() : f[up];
            byPath.put(path, f[p.node()]);
        }
        f[node] = IplPieceMap.NATIVE;
        body.map.relabel(label -> f[label]);
        ChunkPos newMin = live.getPlot().getChunkMin();
        body.map.translate(newMin.x - oldMin.x, newMin.z - oldMin.z);
        Arrays.fill(body.keys, null);
        Arrays.fill(body.parent, -1);
        body.watches.clear();
        body.counts.clear();
        Arrays.fill(body.totals, 0);
        body.movedAt.clear();
        body.owner = live;
        body.sampledPose = new Pose3d(live.logicalPose());
        body.sampledMappings.clear();
        body.recount.addAll(body.map.sectionKeys());
        body.fullPush = true;
        body.fullSync = true;
        body.pendingFlip = null;
        reconcile(body, live);
        settleCounts(body, live);
        seedCurrentFrames(body, live, live.getLevel().getServer().getTickCount());
        // Before the next physics step: the natives still hold the labels of the old frame.
        pushEdges(body, live);
        pushLabels(body, live);
    }

    /** A block of a tracked body changed: recount its section; a placed block joins its neighbours' piece. */
    public static void onBlockChanged(ServerSubLevel ship, BlockPos pos, BlockState state) {
        if (flipping) return;
        Body body = BODIES.get(ship.getUniqueId());
        if (body == null) return;
        long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (body.map.section(key) != null) body.recount.add(key);
        if (state.isAir()) {
            // A removed block must not leave its old piece assignment behind for a
            // later placement at the same position.
            body.map.setLabel(pos.getX(), pos.getY(), pos.getZ(), IplPieceMap.NATIVE);
            return;
        }
        if (!body.hasSessions()) return;
        LevelPlot plot = ship.getPlot();
        int common = -1;
        for (Direction d : Direction.values()) {
            int x = pos.getX() + d.getStepX(), y = pos.getY() + d.getStepY(), z = pos.getZ() + d.getStepZ();
            LevelChunkSection section = sectionAt(plot, x >> 4, y >> 4, z >> 4);
            if (section == null || section.getBlockState(x & 15, y & 15, z & 15).isAir()) continue;
            int label = body.map.label(x, y, z);
            if (common < 0) common = label;
            else if (common != label) return;
        }
        if (common >= 0 && (common == IplPieceMap.NATIVE || body.keys[common] != null)) {
            body.map.setLabel(pos.getX(), pos.getY(), pos.getZ(), common);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Drops bodies whose sub-level is gone (removed, unloaded, merged) and ends their sessions. */
    static void retainLive(MinecraftServer server, Predicate<UUID> alive) {
        Iterator<Body> it = BODIES.values().iterator();
        while (it.hasNext()) {
            Body body = it.next();
            if (alive.test(body.id)) continue;
            IplAtlasStraddleSession.clearShip(body.id, "gone");
            IplStraddleSessionSync.endAllFor(server, body.id, "gone");
            it.remove();
        }
    }

    public static void clearAll() {
        BODIES.clear();
        flipping = false;
        loggedNoNatives = false;
        loggedNodeCap = false;
    }

    // ------------------------------------------------------------------ portals

    /** World bounds of two observed piece poses, with a conservative rotational query margin. */
    private static AABB queryBox(ServerSubLevel ship, Pose3dc before, Pose3dc now) {
        BoundingBox3ic bounds = ship.getPlot().getBoundingBox();
        // Union of actual endpoints plus a conservative rotational arc bound. In contrast
        // to an expanded current AABB this also works for a rotated portal image and >32 b/t.
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        double rotationalReach = 0.0;
        for (int i = 0; i < 8; i++) {
            var point = new org.joml.Vector3d((i & 1) == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                (i & 2) == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                (i & 4) == 0 ? bounds.minZ() : bounds.maxZ() + 1.0);
            var a = before.transformPosition(new org.joml.Vector3d(point)).sub(before.position());
            var b = now.transformPosition(point).sub(now.position());
            rotationalReach = Math.max(rotationalReach, a.distance(b));
        }
        for (Pose3dc pose : new Pose3dc[]{before, now}) {
            for (int i = 0; i < 8; i++) {
                var p = pose.transformPosition(new org.joml.Vector3d(
                    (i & 1) == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                    (i & 2) == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                    (i & 4) == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
                minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(PORTAL_QUERY_INFLATION + rotationalReach);
    }

    static List<Portal> portalsNear(ServerLevel level, AABB query) {
        List<Portal> nearby = new ArrayList<>(level.getEntitiesOfClass(Portal.class, query, Portal::isTeleportable));
        Vec3 center = query.getCenter();
        double reach = 0.5 * Math.sqrt(query.getXsize() * query.getXsize()
            + query.getYsize() * query.getYsize() + query.getZsize() * query.getZsize());
        for (Portal global : GlobalPortalStorage.getGlobalPortals(level)) {
            if (global.isTeleportable() && global.getDistanceToNearestPointInPortal(center) <= reach) {
                nearby.add(global);
            }
        }
        return nearby;
    }

    /**
     * The face at a portal's exit that leads back into it (the reverse portal of the pair and
     * its flipped side) is never an entrance for the piece that came out of it. A coplanar
     * portal with another destination is an honest next entrance.
     */
    private static boolean isExitFace(Portal entered, Portal candidate) {
        if (!candidate.level().dimension().equals(entered.getDestDim())) return false;
        if (candidate.getOriginPos().distanceToSqr(entered.getDestPos()) > 1.0e-6) return false;
        Vec3 exitNormal = entered.transformLocalVec(entered.getNormal());
        if (Math.abs(candidate.getNormal().dot(exitNormal)) <= 0.9999) return false;
        return candidate.getDestDim().equals(entered.level().dimension())
            && candidate.getDestPos().distanceToSqr(entered.getOriginPos()) <= 1.0e-4;
    }

    /**
     * Same-normal duplicates of one doorway (identical rectangle, destination and isometry)
     * are one entrance: the face that already has a session, else the smallest UUID.
     */
    private static boolean isCanonicalFace(Portal portal, List<Portal> candidates, Predicate<Portal> hasSession) {
        for (Portal other : candidates) {
            if (other == portal || !portal.getDestDim().equals(other.getDestDim())) continue;
            if (!sameRectangle(portal, other) || portal.getNormal().dot(other.getNormal()) < 0.999999
                || portal.getDestPos().distanceToSqr(other.getDestPos()) > 1.0e-12) continue;
            var mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
            var otherMapping = IplStraddlePoseMap.StraddleMapping.of(other);
            if (mapping.mapVec(new Vec3(1, 0, 0)).distanceToSqr(otherMapping.mapVec(new Vec3(1, 0, 0))) > 1e-12
                || mapping.mapVec(new Vec3(0, 1, 0)).distanceToSqr(otherMapping.mapVec(new Vec3(0, 1, 0))) > 1e-12
                || Math.abs(portal.getScaling() - other.getScaling()) > 1e-9) continue;
            boolean own = hasSession.test(portal);
            boolean theirs = hasSession.test(other);
            if (own != theirs) {
                if (theirs) return false;
                continue;
            }
            if (portal.getUUID().toString().compareTo(other.getUUID().toString()) > 0) return false;
        }
        return true;
    }

    private static boolean sameRectangle(Portal a, Portal b) {
        if (a.level() != b.level() || a.getOriginPos().distanceToSqr(b.getOriginPos()) > 1e-12
            || Math.abs(a.getNormal().dot(b.getNormal())) < .999999) return false;
        boolean direct = Math.abs(a.getWidth() - b.getWidth()) < 1e-6
            && Math.abs(a.getHeight() - b.getHeight()) < 1e-6
            && Math.abs(a.getAxisW().dot(b.getAxisW())) > .999999
            && Math.abs(a.getAxisH().dot(b.getAxisH())) > .999999;
        boolean swapped = Math.abs(a.getWidth() - b.getHeight()) < 1e-6
            && Math.abs(a.getHeight() - b.getWidth()) < 1e-6
            && Math.abs(a.getAxisW().dot(b.getAxisH())) > .999999
            && Math.abs(a.getAxisH().dot(b.getAxisW())) > .999999;
        return direct || swapped;
    }

    // ------------------------------------------------------------------ plot access

    @Nullable
    private static LevelChunkSection sectionAt(LevelPlot plot, int sx, int sy, int sz) {
        LevelChunk chunk = plot.getChunk(plot.toLocal(new ChunkPos(sx, sz)));
        if (chunk == null) return null;
        int index = chunk.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] sections = chunk.getSections();
        return index < 0 || index >= sections.length ? null : sections[index];
    }
}
