package ipl.sable.client;

import com.mojang.datafixers.util.Pair;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import ipl.sable.transit.IplProjectionRaycast;
import ipl.sable.transit.IplProjectionTree;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.DQuaternion;
import qouteall.q_misc_util.my_util.RayTraceResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Through-portal pick for straddling sub-levels.
 *
 * <p>Sable's raycast overlay transforms the ray into each intersecting sub-level's local
 * space via the level pose provider — which is unmapped for a ship straddling in from
 * another dimension, so picks at the through-part miss. This helper raycasts the SAME ray
 * through each straddle projection's MAPPED pose instead: the ray is inverse-transformed
 * into plot space and clipped there (block reads resolve through the plot bridge from any
 * dimension). The returned {@link BlockHitResult} is in PLOT coordinates, matching Sable's
 * own convention for sub-level hits — so {@code getContainingClient(hit.getLocation())}
 * and everything downstream (drag anchors, lock points) work unchanged.
 *
 * <p>This class owns pick/drag CAPTURE state only. The frame algebra of an active drag
 * lives in the grab chain ({@link IplGrabChainClient} / server {@code IplGrabChain});
 * beam geometry lives in {@link IplStaffBeamRoutes}. The pick's job at grab start is to
 * hand the chain its seed: the exact portal path the pick ray traversed, plus — when the
 * click landed on a straddle IMAGE rather than the native half — the full session path whose
 * inverse links the image's frame back to the body's parent frame. Which representation
 * was clicked is resolved geometrically against the actual ray (both candidate positions
 * are computed and the one the ray intersects wins), not guessed from player position.
 */
public final class IplStraddleStaffPick {

    /** Last pick, awaiting confirmation by Simulated's drag-session creation. */
    private static final Map<UUID, PortalTarget> PENDING_TARGETS = new HashMap<>();

    /** Locked drag frame. It never changes because a later hover happened to hit a portal. */
    private static final Map<UUID, PortalTarget> DRAG_TARGETS = new HashMap<>();

    /**
     * VISIBLE ray length from eye to the picked point, captured at pick, keyed by sub-level.
     * Stock derives the hold distance from {@code logicalPose} (the body's NATIVE position),
     * which is a different coordinate space for a cross-dimension grab and a different
     * location for a same-dimension image grab. The physical pick already walked the true
     * distance the ray travelled (rigid mappings preserve length); the drag session and the
     * beam's initial node density both use it.
     */
    private static final Map<UUID, Double> GRAB_DISTANCE = new HashMap<>();

    /**
     * Blocks whose hit lies within this distance of a portal plane (or beyond it) are behind
     * that portal: a doorway built flush on a wall, a floor portal on the ground, the frame
     * around a doorway. Ray and plane distances of such blocks differ only by rounding.
     */
    private static final double PLANE_TOLERANCE = 1.0e-3;

    /** A pick's hit point is handed on unchanged; equality only has to absorb rounding. */
    private static final double SAME_POINT_DISTANCE_SQ = 1.0e-12;

    private IplStraddleStaffPick() {}

    /**
     * Visible distance at which the staff ray enters the first portal in the player's own
     * world, or {@link Double#MAX_VALUE}. Same portal filter as the through-portal walk, so
     * "behind the portal" means exactly what that walk treats as the other side.
     */
    public static double firstPortalDistance(Player player, double range, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 end = eye.add(player.getViewVector(partialTick).scale(range));
        return PortalUtils.raytracePortals(
                player.level(), eye, end, true, portal -> portal.isInteractableBy(player))
            .map(hit -> hit.getSecond().hitPos().distanceTo(eye))
            .orElse(Double.MAX_VALUE);
    }

    /**
     * Whether a hit at {@code visibleDistance} lies at or behind the first portal plane the ray
     * enters ({@link #firstPortalDistance}): the portal shows the other side there, so such a hit
     * is invisible. Unresolved distances ({@link Double#MAX_VALUE}) are never "behind".
     */
    public static boolean isBehindPortal(double visibleDistance, double portalDistance) {
        return portalDistance < Double.MAX_VALUE && visibleDistance < Double.MAX_VALUE
            && visibleDistance >= portalDistance - PLANE_TOLERANCE;
    }

    /** "Nothing hit" at the place of {@code hit}, for a ray along the player's view. */
    public static BlockHitResult missAt(Player player, HitResult hit, float partialTick) {
        Vec3 view = player.getViewVector(partialTick);
        return BlockHitResult.miss(hit.getLocation(),
            Direction.getNearest(view.x, view.y, view.z), BlockPos.containing(hit.getLocation()));
    }

    /**
     * The exact client object the last pick resolved for this plot-space hit point, in ANY
     * client level. Simulated looks the hit up in the player's own level only, which never
     * finds a body living in another dimension (seen through a portal), and cannot tell a
     * transit ghost from its successor when both occupy the same plot slot.
     */
    @Nullable
    public static ClientSubLevel pickedSubAt(Vec3 plotPoint) {
        for (PortalTarget target : PENDING_TARGETS.values()) {
            if (target.hit().getLocation().distanceToSqr(plotPoint) < SAME_POINT_DISTANCE_SQ
                && !target.sub().isRemoved()) {
                return target.sub();
            }
        }
        return null;
    }

    /** Best projection hit for a ray, or null. Used by the staff fallback. */
    @Nullable
    public static HitResult pickStraddleProjections(Player player, double range, float partialTick) {
        if (!(player.level() instanceof ClientLevel level)) return null;
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 end = eye.add(player.getViewVector(partialTick).scale(range));
        ProjectionHit best = clipProjections(level, new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return best != null ? best.hit() : null;
    }

    /**
     * Raycast through IP portals, including straddle projections in every reached world.
     * Returns only Sable targets; ordinary portal block targeting remains IP's responsibility.
     */
    @Nullable
    public static PortalTarget pickThroughPortals(Player player, double range, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        PortalTarget target = pickThroughPortals(
            player, player.level(), eye, player.getViewVector(partialTick), range
        );
        if (target == null) return null;
        // Exactly ONE pick may be pending at a time: a capture left over from an aborted pick
        // would be consumed by startDraggingSubLevel and bind a freshly grabbed build to a
        // portal path it was never picked through.
        PENDING_TARGETS.clear();
        PENDING_TARGETS.put(target.sub().getUniqueId(), target);
        GRAB_DISTANCE.put(target.sub().getUniqueId(), target.visibleDistance());
        return target;
    }

    /** Portal recursion with Sable plot hits compared in their visible world frame. */
    @Nullable
    private static PortalTarget pickThroughPortals(
        Player player, Level level, Vec3 from, Vec3 direction, double remaining
    ) {
        List<Portal> path = new ArrayList<>();
        double traveled = 0.0;
        // Suppress a ray that cycles without covering any meaningful distance. This is
        // a numerical loop guard, not a limit on the number of portals in a path.
        Set<String> stationary = new java.util.HashSet<>();
        while (level != null && remaining > 1.0e-6) {
            Vec3 to = from.add(direction.scale(remaining));
            BlockHitResult block = level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            IplProjectionRaycast.FrameHit frame = IplProjectionRaycast.lastWinner(block);
            ClientSubLevel sub = block.getType() == HitResult.Type.MISS ? null
                : frame != null && frame.sub() instanceof ClientSubLevel frameSub ? frameSub
                : getHitSubLevel(level, block);
            VisibleHit visible = null;
            double blockDistance = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE
                : from.distanceTo(block.getLocation());
            if (sub != null) {
                if (frame != null) {
                    blockDistance = frame.distance();
                    visible = new VisibleHit(blockDistance,
                        frame.node() == null ? List.of() : frame.node().pathPortals());
                } else {
                    visible = visibleHit(level, from, to, sub, block);
                    blockDistance = visible == null ? Double.MAX_VALUE : visible.distance();
                }
            }
            Optional<Pair<Portal, RayTraceResult>> portalHit = PortalUtils.raytracePortals(
                level, from, to, true, portal -> portal.isInteractableBy(player));
            double portalDistance = portalHit.isPresent()
                ? portalHit.get().getSecond().hitPos().distanceTo(from) : Double.MAX_VALUE;
            if (visible != null && blockDistance <= portalDistance + 0.0001) {
                return new PortalTarget(sub, level, block, List.copyOf(path),
                    visible.imagePath(), traveled + blockDistance);
            }
            // A block flush with (or behind) the portal plane is behind the doorway, not in
            // front of it: only a block clearly before the plane stops the ray.
            if (block.getType() != HitResult.Type.MISS
                && blockDistance < portalDistance - PLANE_TOLERANCE) return null;
            if (portalHit.isEmpty() || portalDistance >= remaining) return null;
            Portal portal = portalHit.get().getFirst();
            if (portalDistance < 1.0e-5) {
                String state = level.dimension().location() + ":" + portal.getUUID();
                if (!stationary.add(state)) return null;
            } else {
                stationary.clear();
            }
            path.add(portal);
            Vec3 transformedDirection = portal.transformLocalVecNonScale(direction).normalize();
            // Move just beyond the exit along the transformed ray, not along the source
            // normal (which can point into a completely different direction after rotation).
            double skip = Math.min(1.0e-4, remaining - portalDistance);
            from = portal.transformPoint(portalHit.get().getSecond().hitPos())
                .add(transformedDirection.scale(skip));
            direction = transformedDirection;
            level = portal.getDestinationWorld();
            remaining -= portalDistance + skip;
            traveled += portalDistance + skip;
        }
        return null;
    }

    /**
     * Which visible representation of {@code sub} did this ray actually hit? Both concrete
     * candidates are computed — the native pose (when the body is native to {@code level})
     * and the straddle-session image (when one projects into {@code level}) — and the one
     * geometrically ON the ray wins. Exact candidate resolution, not a side heuristic; for
     * a same-dimension straddle both candidates exist and this is the only correct
     * disambiguation of "grabbed the emerged half" vs "grabbed the native half".
     */
    @Nullable
    private static VisibleHit visibleHit(
        Level level, Vec3 from, Vec3 to, ClientSubLevel sub, BlockHitResult hit
    ) {
        // Every frame through which the ship is present in `level` (native piece + each
        // session image, chains included) is a candidate; the one ON the ray wins.
        Vec3[] nearest = {null};
        double[] nearestError = {Double.MAX_VALUE};
        IplProjectionTree.Node[] chosen = {null};
        Pose3dc real = sub.renderPose();
        IplProjectionTree.forEachFrameIn(sub, level, node -> {
            Pose3dc pose = node == null ? real : node.mapping.mapPose(real);
            Vec3 candidate = pose.transformPosition(hit.getLocation());
            double error = rayErrorSq(from, to, candidate);
            if (error < nearestError[0]) {
                nearestError[0] = error;
                nearest[0] = candidate;
                chosen[0] = node;
            }
        });
        if (nearest[0] == null) return null;
        return new VisibleHit(from.distanceTo(nearest[0]),
            chosen[0] == null ? List.of() : chosen[0].pathPortals());
    }

    private record VisibleHit(double distance, List<Portal> imagePath) {}

    private static double rayErrorSq(Vec3 from, Vec3 to, Vec3 point) {
        Vec3 ray = to.subtract(from);
        double lengthSq = ray.lengthSqr();
        if (lengthSq < 1.0e-12) return point.distanceToSqr(from);
        double t = Math.clamp(point.subtract(from).dot(ray) / lengthSq, 0.0, 1.0);
        return point.distanceToSqr(from.add(ray.scale(t)));
    }

    /** Current world render path, ordered from player world to current world. */
    public static List<Portal> getActiveRenderPath() {
        if (!PortalRendering.isRendering()) {
            return List.of();
        }
        return PortalRendering.getPortalPath();
    }

    /**
     * Simulated created a drag session for this selected ship: lock the captured pick and
     * seed the grab chain from it (traversed portals forward, image session inverse).
     */
    public static void beginDrag(ClientSubLevel sub) {
        seedFrom(sub, PENDING_TARGETS.remove(sub.getUniqueId()));
    }

    /**
     * LOCK ("fixate") path. Simulated routes a lock through the same pick as a grab but
     * never enters {@code startDraggingSubLevel}, so the portal chain was never seeded and
     * the beam had nothing to fold through: it was drawn straight to the body's RAW world
     * coordinates instead of through the portal. A lock is the same optical situation as a
     * grab, so it publishes the same chain -- the only difference is that the pending
     * capture is not consumed, because a following grab on the same body must still see it.
     */
    public static void beginLock(ClientSubLevel sub) {
        // Locking the body being dragged: its chain is already live (and may hold transits the
        // pick never saw). Re-seeding it from an empty pick would only be rejected by the server.
        if (IplGrabChainClient.hasLocalChainFor(sub.getUniqueId())) return;
        seedFrom(sub, PENDING_TARGETS.get(sub.getUniqueId()));
    }

    /** Lock the captured pick of {@code sub} (if it is this object's) and seed the grab chain. */
    private static void seedFrom(ClientSubLevel sub, @Nullable PortalTarget selected) {
        UUID id = sub.getUniqueId();
        long frame = IplParentDimSync.pickFrame(sub);
        if (selected == null || selected.sub() != sub) {
            DRAG_TARGETS.remove(id);
            IplGrabChainClient.seedLocal(id, List.of(), List.of(), frame);
            return;
        }
        DRAG_TARGETS.put(id, selected);
        IplGrabChainClient.seedLocal(id, selected.portals(), selected.imagePath(), frame);
    }

    public static void clearDragTargets() {
        PENDING_TARGETS.clear();
        DRAG_TARGETS.clear();
        GRAB_DISTANCE.clear();
        IplGrabChainClient.clearLocal();
        IplStaffBeamRoutes.clearAll();
    }

    /**
     * Overwrite the fresh drag session's hold distance with the true visible pick distance.
     * Called at the TAIL of {@code startDraggingSubLevel}, after Simulated created the session
     * with its native-pose distance. No-op for a plain local grab (no captured pick distance),
     * so ordinary same-world dragging keeps stock behavior.
     */
    public static void applyGrabDistance(PhysicsStaffClientHandler handler,
                                         SubLevel sub) {
        Double distance = GRAB_DISTANCE.get(sub.getUniqueId());
        if (distance == null) return;
        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        if (session == null || !session.dragSubLevel().getUniqueId().equals(sub.getUniqueId())) return;
        session.setDistance(Math.clamp(distance, 2.0, PhysicsStaffItem.RANGE));
    }

    /**
     * VISIBLE ray length from the eye to whatever this hit actually represents, or
     * {@link Double#MAX_VALUE} when there is nothing comparable to measure.
     *
     * <p>Sub-level hits report PLOT coordinates, so their raw location cannot be measured
     * against the eye directly. The same candidate resolution the through-portal pick uses
     * maps them into the visible frame first, which is the only frame in which a local hit
     * and a through-portal hit are comparable at all.
     */
    public static double visibleRayLength(Player player, @Nullable HitResult hit, float partialTick) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) return Double.MAX_VALUE;
        if (!(player.level() instanceof ClientLevel level)) return Double.MAX_VALUE;
        Vec3 eye = player.getEyePosition(partialTick);
        if (hit instanceof BlockHitResult blockHit) {
            ClientSubLevel sub = getHitSubLevel(level, blockHit);
            if (sub != null) {
                Vec3 end = eye.add(player.getViewVector(partialTick).scale(PhysicsStaffItem.RANGE));
                VisibleHit visible = visibleHit(level, eye, end, sub, blockHit);
                return visible == null ? Double.MAX_VALUE : visible.distance();
            }
        }
        return eye.distanceTo(hit.getLocation());
    }

    /**
     * Drop a speculative through-portal capture the pick did not end up choosing.
     * {@link #pickThroughPortals(Player, double, float)} records its result eagerly (the
     * recursion is the only place the traversed portal path exists), so whoever rejects
     * that result owns the cleanup. Without it the rejected capture stays pending and the
     * next drag session seeds a portal chain for a body that was grabbed locally.
     */
    public static void discardTarget(PortalTarget target) {
        UUID id = target.sub().getUniqueId();
        PENDING_TARGETS.remove(id);
        GRAB_DISTANCE.remove(id);
    }

    /** True pick-ray length for this sub's last pick, or NaN (beam creation node density). */
    public static double pickDistance(UUID subId) {
        Double distance = GRAB_DISTANCE.get(subId);
        return distance == null ? Double.NaN : distance;
    }

    /**
     * Convert the staff's mouse pitch axis into the grabbed body's constraint frame.
     *
     * <p>Derivation: the player rotates what they SEE. With E the beam route's frame fold
     * (rotation composition of its links) and M the image-refinement rotation when the beam
     * targets a straddle image (identity otherwise), the visible representation v relates to
     * the native orientation o by v = M·o, and the axis the player means, expressed in the
     * visible frame, is R_E·a. Solving ΔQ(axis_constraint)·o = M⁻¹·ΔQ(R_E·a)·M·o gives
     * axis_constraint = M⁻¹·R_E·a — computed by {@link IplStaffBeamRoutes#axisRotation}
     * from the same route the beam draws. One frame source for goal, beam, aim, and axis.
     */
    public static Vec3 unmapStaffInputAxis(Player player, ClientSubLevel sub, Vec3 axis) {
        PhysicsStaffClientHandler.ClientDragSession session =
            SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
        Vec3 localAnchor = session != null
            && session.dragSubLevel().getUniqueId().equals(sub.getUniqueId())
            ? new Vec3(session.dragLocalAnchor().x(), session.dragLocalAnchor().y(),
                session.dragLocalAnchor().z())
            : Vec3.ZERO;
        Quaterniond rotation = IplStaffBeamRoutes.axisRotation(player, sub, localAnchor);
        if (rotation == null) return axis;
        Vector3d mapped = rotation.transform(new Vector3d(axis.x, axis.y, axis.z));
        return new Vec3(mapped.x, mapped.y, mapped.z);
    }

    /**
     * Keep a local target's clicked representation too, not only portal-traversed ones. The
     * same geometric candidate resolution as the through-portal pick decides whether the
     * click landed on the native half or on a straddle image projected into this world —
     * for a same-dimension straddle both exist, and grabbing the emerged half must link the
     * image frame (its session portal's inverse), or the constraint would pull it back in.
     */
    public static void rememberLocalProjection(Player player, HitResult hit) {
        if (!(player.level() instanceof ClientLevel level) || !(hit instanceof BlockHitResult blockHit)) return;
        ClientSubLevel sub = getHitSubLevel(level, blockHit);
        if (sub == null) return;

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(PhysicsStaffItem.RANGE));
        VisibleHit visible = visibleHit(level, eye, end, sub, blockHit);
        if (visible == null) {
            PENDING_TARGETS.remove(sub.getUniqueId());
            DRAG_TARGETS.remove(sub.getUniqueId());
            GRAB_DISTANCE.remove(sub.getUniqueId());
            return;
        }

        if (visible.imagePath().isEmpty()
            && sub.getLevel() == level) {
            // Plain native grab in the body's own world: stock behavior, no capture.
            PENDING_TARGETS.remove(sub.getUniqueId());
            DRAG_TARGETS.remove(sub.getUniqueId());
            GRAB_DISTANCE.remove(sub.getUniqueId());
            return;
        }

        PENDING_TARGETS.clear();
        PENDING_TARGETS.put(sub.getUniqueId(), new PortalTarget(
            sub, level, blockHit, List.of(), visible.imagePath(), visible.distance()
        ));
        GRAB_DISTANCE.put(sub.getUniqueId(), visible.distance());
    }

    @Nullable
    private static ClientSubLevel getHitSubLevel(Level level, BlockHitResult hit) {
        // `level` is the visible source/destination world during portal recursion; the hit
        // location is in PLOT coordinates, whose owner may be a sub-level of another client
        // level (a projection). Resolve the plot across client levels.
        ClientSubLevel sub = IplClientProjections.findSubLevelAtPlot(hit.getLocation());
        return sub;
    }

    /**
     * Pose of a captured remote target in the current staff render pass. Remove only the
     * portal suffix not traversed by this render pass, so every recursive pass receives its
     * target in its own world frame. (Held staff ITEM rendering only; beam geometry is
     * chain-driven in {@link IplStaffBeamRoutes}.)
     */
    public static Pose3d mapStaffRenderPose(ClientSubLevel sub, Pose3dc pose) {
        PortalTarget target = DRAG_TARGETS.get(sub.getUniqueId());
        if (target == null || target.sub() != sub
            || (target.portals().isEmpty() && target.imagePath().isEmpty())) {
            Minecraft mc = Minecraft.getInstance();
            Level renderLevel = mc.level;
            if (PortalRendering.isRendering()) {
                renderLevel = PortalRendering
                    .getRenderingPortal().getDestinationWorld();
            }
            IplStraddlePoseMap.StraddleMapping mapping = renderLevel == null
                ? null
                : IplStraddlePoseMap.getMappingInto(sub, renderLevel);
            return mapping == null ? new Pose3d(pose) : mapping.mapPose(pose);
        }
        Pose3d mapped = new Pose3d(pose);
        // Pose starts in the body's frame; the picked image is reached by the
        // entire session path, not just its last portal.
        for (Portal portal : target.imagePath()) {
            Vec3 position = portal.transformPoint(new Vec3(
                mapped.position().x(), mapped.position().y(), mapped.position().z()));
            DQuaternion rotation = portal.getRotationD();
            mapped.position().set(position.x, position.y, position.z);
            if (rotation != null) mapped.orientation().set(new Quaterniond(
                rotation.x, rotation.y, rotation.z, rotation.w).mul(mapped.orientation()));
        }
        List<Portal> renderPath = getActiveRenderPath();
        int traversed = 0;
        while (traversed < renderPath.size() && traversed < target.portals().size()
            && renderPath.get(traversed) == target.portals().get(traversed)) {
            traversed++;
        }
        // Target pose starts in final destination frame. Undo only portal suffix that this
        // render pass has not traversed. Thus outer world gets its source-side endpoint, first
        // portal render gets its intermediate endpoint, and final recursive pass gets native
        // target coordinates.
        for (int i = target.portals().size() - 1; i >= traversed; i--) {
            Portal portal = target.portals().get(i);
            Vec3 position = portal.inverseTransformPoint(new Vec3(
                mapped.position().x(), mapped.position().y(), mapped.position().z()
            ));
            DQuaternion rotation = portal.getRotationD();
            mapped.position().set(position.x, position.y, position.z);
            if (rotation != null) {
                Quaterniond inverse = new Quaterniond(
                    rotation.x, rotation.y, rotation.z, rotation.w
                ).invert();
                mapped.orientation().set(inverse.mul(mapped.orientation()));
            }
        }
        return mapped;
    }

    /**
     * A captured pick target: the exact forward portal path the ray traversed, plus the
     * straddle-session portal whose IMAGE was clicked (null when the native half was).
     */
    public record PortalTarget(
        ClientSubLevel sub, Level world, BlockHitResult hit,
        List<Portal> portals, List<Portal> imagePath, double visibleDistance
    ) {}

    /** A projection hit: plot-coordinate BlockHitResult + ray distance² (frame-comparable). */
    public record ProjectionHit(
        BlockHitResult hit, double distSq,
        @Nullable ClientSubLevel sub, @Nullable Portal imagePortal
    ) {}

    /**
     * Clip the context's ray against every straddle projection into {@code level}, using
     * MAPPED poses. The local clip resolves plot blocks through the plot bridge; the
     * returned hit is in PLOT coordinates (Sable's sub-level hit convention). Distances
     * are frame-comparable because the mapping is rigid.
     *
     * <p>Re-entrant calls (our own projection clips go through {@code level.clip}) are
     * guarded by the caller.
     */
    @Nullable
    public static ProjectionHit clipProjections(ClientLevel level, ClipContext ctx) {
        List<IplClientProjections.StraddleProjection> projections =
            IplClientProjections.getStraddleProjectionsInto(level);
        if (projections.isEmpty()) return null;

        Vec3 from = ctx.getFrom();
        Vec3 to = ctx.getTo();
        Set<ClientSubLevel> seen =
            Collections.newSetFromMap(new IdentityHashMap<>());
        IplProjectionRaycast.FrameHit best = null;
        for (IplClientProjections.StraddleProjection projection : projections) {
            if (!seen.add(projection.sub())) continue;
            // The native piece is handled by the stock pick; all image depths are eligible.
            IplProjectionRaycast.FrameHit hit = IplProjectionRaycast.clipSubLevel(
                level, ctx, from, to, projection.sub(), false, node -> true);
            if (hit != null && (best == null || hit.distance() < best.distance())) best = hit;
        }
        if (best == null || !(best.sub() instanceof ClientSubLevel bestSub)) return null;
        return new ProjectionHit(best.hit(), best.distance() * best.distance(), bestSub,
            best.node() == null ? null : best.node().portal);
    }
}
