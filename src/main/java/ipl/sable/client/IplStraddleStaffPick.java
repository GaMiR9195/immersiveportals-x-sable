package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import com.mojang.datafixers.util.Pair;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Through-portal pick for straddling hosted sub-levels.
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
 * <p>Distances compare correctly across frames because the mapping is rigid
 * (translation-only portal pairs preserve lengths).
 *
 * <p>Beam GEOMETRY lives in {@link IplStaffBeamRoutes}; this class owns pick/drag capture
 * state and exposes it to the route builder.
 */
public final class IplStraddleStaffPick {

    /** Last pick, awaiting confirmation by Simulated's drag-session creation. */
    private static final Map<UUID, PortalTarget> PENDING_TARGETS = new HashMap<>();

    /** Locked drag frame. It never changes because a later hover happened to hit a portal. */
    private static final Map<UUID, PortalTarget> DRAG_TARGETS = new HashMap<>();

    /** Most recent completed body crossing, retained while drag continues across its reverse. */
    private static final Map<UUID, TransitFrame> TRANSIT_FRAMES = new HashMap<>();

    /** Same-dimension mouse-axis chooser stickiness, keyed by grabbed sub-level. */
    private static final Map<UUID, Boolean> SAME_DIM_AXIS_MAPPED = new HashMap<>();

    /** Same-dimension chooser margin (client mirror of the server goal chooser). */
    private static final double SAME_DIM_SWITCH_MARGIN = 1.5;

    /**
     * VISIBLE ray length from eye to the picked point, captured at grab, keyed by sub-level.
     * Stock derives the hold distance from {@code logicalPose} (the body's NATIVE position),
     * which is a different coordinate space for a cross-dimension grab and a different location
     * for a same-dimension image grab — producing a maxed-out or plain wrong hold distance that
     * yanks the body somewhere it was never grabbed. The physical pick already walked the true
     * distance the ray travelled; we hand that to the drag session instead. One-shot: consumed
     * when the session is created.
     */
    private static final Map<UUID, Double> GRAB_DISTANCE = new HashMap<>();

    private IplStraddleStaffPick() {}

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
            player, player.level(), eye, player.getViewVector(partialTick), range, List.of(), 0, 0.0
        );
        if (target == null) return null;
        PENDING_TARGETS.put(target.sub().getUniqueId(), target);
        GRAB_DISTANCE.put(target.sub().getUniqueId(), target.visibleDistance());
        return target;
    }

    /** Portal recursion with Sable plot hits compared in their visible world frame. */
    @Nullable
    private static PortalTarget pickThroughPortals(
        Player player, Level level, Vec3 from, Vec3 direction, double remaining,
        List<Portal> path, int depth, double traveled
    ) {
        if (depth > 3 || remaining <= 0.0) return null;

        Vec3 to = from.add(direction.scale(remaining));
        BlockHitResult block = level.clip(new ClipContext(
            from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
        ));
        ClientSubLevel sub = block.getType() == HitResult.Type.MISS ? null : getHitSubLevel(level, block);
        double blockDistance = sub == null ? Double.MAX_VALUE : visibleHitDistance(level, from, to, sub, block);

        Optional<Pair<Portal, qouteall.q_misc_util.my_util.RayTraceResult>> portalHit =
            qouteall.imm_ptl.core.portal.PortalUtils.raytracePortals(
                level, from, to, true, portal -> portal.isInteractableBy(player)
            );
        double portalDistance = portalHit.map(hit -> hit.getSecond().hitPos().distanceTo(from))
            .orElse(Double.MAX_VALUE);

        if (sub != null && blockDistance <= portalDistance + 0.0001) {
            // traveled through prior frames + the visible reach within this frame = the true
            // ray length to the grabbed point (mappings are rigid, so lengths add).
            return portalTargetForHit(sub, level, block, path, traveled + blockDistance);
        }
        if (block.getType() != HitResult.Type.MISS && portalDistance > blockDistance) {
            return null;
        }
        if (portalHit.isEmpty()) return null;

        Portal portal = portalHit.get().getFirst();
        qouteall.q_misc_util.my_util.RayTraceResult trace = portalHit.get().getSecond();
        double rest = remaining - portalDistance;
        if (rest <= 0.0) return null;

        java.util.ArrayList<Portal> nextPath = new java.util.ArrayList<>(path);
        nextPath.add(portal);
        return pickThroughPortals(
            player,
            portal.getDestinationWorld(),
            portal.transformPoint(trace.hitPos()).add(trace.surfaceNormal().scale(-0.001)),
            portal.transformLocalVecNonScale(direction),
            rest,
            List.copyOf(nextPath),
            depth + 1,
            traveled + portalDistance
        );
    }

    private static PortalTarget portalTargetForHit(
        ClientSubLevel sub, Level level, BlockHitResult hit, List<Portal> path, double visibleDistance
    ) {
        return new PortalTarget(sub, level, hit, path, false, visibleDistance);
    }

    private static double visibleHitDistance(
        Level level, Vec3 from, Vec3 to, ClientSubLevel sub, BlockHitResult hit
    ) {
        Vec3 nearest = null;
        double nearestError = Double.MAX_VALUE;
        if (ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) == level) {
            Vec3 candidate = sub.renderPose().transformPosition(hit.getLocation());
            nearest = candidate;
            nearestError = rayErrorSq(from, to, candidate);
        }

        IplClientHostedLookup.StraddleProjection projection =
            IplClientHostedLookup.getStraddleProjectionFor(sub);
        if (projection != null && projection.portal().getDestDim() == level.dimension()) {
            Vec3 candidate = projection.mappedPose().transformPosition(hit.getLocation());
            double error = rayErrorSq(from, to, candidate);
            if (error < nearestError) {
                nearest = candidate;
                nearestError = error;
            }
        }
        return nearest == null ? Double.MAX_VALUE : from.distanceTo(nearest);
    }

    private static double rayErrorSq(Vec3 from, Vec3 to, Vec3 point) {
        Vec3 ray = to.subtract(from);
        double lengthSq = ray.lengthSqr();
        if (lengthSq < 1.0e-12) return point.distanceToSqr(from);
        double t = Math.clamp(point.subtract(from).dot(ray) / lengthSq, 0.0, 1.0);
        return point.distanceToSqr(from.add(ray.scale(t)));
    }

    /** Current world render path, ordered from player world to current world. */
    public static List<Portal> getActiveRenderPath() {
        if (!qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            return List.of();
        }
        return qouteall.imm_ptl.core.render.context_management.PortalRendering.getPortalPath();
    }

    /** Lock portal path only after Simulated created a drag session for this selected ship. */
    public static void beginDrag(ClientSubLevel sub) {
        TRANSIT_FRAMES.remove(sub.getUniqueId());
        SAME_DIM_AXIS_MAPPED.remove(sub.getUniqueId());
        PortalTarget selected = PENDING_TARGETS.remove(sub.getUniqueId());
        if (selected == null || selected.sub() != sub) {
            DRAG_TARGETS.remove(sub.getUniqueId());
            return;
        }
        DRAG_TARGETS.put(sub.getUniqueId(), selected);
    }

    public static void clearDragTargets() {
        PENDING_TARGETS.clear();
        DRAG_TARGETS.clear();
        TRANSIT_FRAMES.clear();
        SAME_DIM_AXIS_MAPPED.clear();
        GRAB_DISTANCE.clear();
        IplStaffBeamRoutes.clearAll();
    }

    /**
     * Overwrite the fresh drag session's hold distance with the true visible pick distance.
     * Called at the TAIL of {@code startDraggingSubLevel}, after Simulated created the session
     * with its native-pose distance. No-op for a plain local grab (no captured pick distance),
     * so ordinary same-world dragging keeps stock behavior.
     */
    public static void applyGrabDistance(PhysicsStaffClientHandler handler,
                                         dev.ryanhcode.sable.sublevel.SubLevel sub) {
        Double distance = GRAB_DISTANCE.remove(sub.getUniqueId());
        if (distance == null) return;
        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        if (session == null || !session.dragSubLevel().getUniqueId().equals(sub.getUniqueId())) return;
        session.setDistance(Math.clamp(distance, 2.0, PhysicsStaffItem.RANGE));
    }

    /**
     * Parent handoff changes the body's native frame. Keep exact crossing data while drag stays
     * active: server maps raw cursor goals after this boundary, and mouse axes rotate with it.
     */
    public static void onTransitHandoff(
        UUID subId, ResourceKey<Level> sourceDim, ResourceKey<Level> targetDim,
        Vec3 origin, Vec3 sourceNormal, org.joml.Quaterniondc rotation, UUID portalId
    ) {
        PENDING_TARGETS.remove(subId);
        DRAG_TARGETS.remove(subId);
        // Same-dimension transits never happen while held (staff freeze); one arriving here
        // means the drag already ended or is ending — record nothing, rotate nothing.
        if (sourceDim.equals(targetDim)) {
            TRANSIT_FRAMES.remove(subId);
            SAME_DIM_AXIS_MAPPED.remove(subId);
            return;
        }
        TRANSIT_FRAMES.put(subId, new TransitFrame(
            sourceDim, targetDim, origin, sourceNormal,
            new org.joml.Quaterniond(rotation), portalId
        ));

        dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.ClientDragSession session =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
        if (session != null && session.dragSubLevel().getUniqueId().equals(subId)) {
            session.dragOrientation().set(new org.joml.Quaterniond(rotation).mul(session.dragOrientation()));
        }
    }

    /**
     * Convert Simulated's outgoing relative drag vector before packet construction. The stock
     * packet has no portal/frame field and server reconstructs its goal as `serverEye + vector`.
     * The packet stays raw in the player's physical world frame for the entire drag; the
     * server's geometric goal mapper owns every frame decision.
     */
    public static Vector3d mapOutgoingDragGoal(Player player, UUID subId, org.joml.Vector3dc relativeGoal) {
        // Straddle sessions and completed transits are mapped SERVER-side (it owns that
        // geometry); pre-mapping them too was the old double-transform flight bug. But a pure
        // remote grab — body fully in another dimension, reached only through portals, no
        // straddle session — is never mapped server-side, so the raw player-frame goal lands
        // in the wrong dimension's coordinates and the body flies off (then vanishes until you
        // follow it). For that case ONLY, walk the captured portal chain here so the goal
        // arrives in the body's frame; the server passes it through untouched.
        List<Portal> captured = capturedPortals(subId, player.level());
        if (captured == null || captured.isEmpty()) return new Vector3d(relativeGoal);
        if (IplStraddleSessionStore.hasSession(subId) || TRANSIT_FRAMES.containsKey(subId)) {
            return new Vector3d(relativeGoal);
        }
        Vec3 eye = player.getEyePosition();
        Vec3 absolute = eye.add(relativeGoal.x(), relativeGoal.y(), relativeGoal.z());
        for (Portal portal : captured) absolute = portal.transformPoint(absolute);
        // Server rebuilds goal as serverEye + this vector, so send it eye-relative.
        return new Vector3d(absolute.x - eye.x, absolute.y - eye.y, absolute.z - eye.z);
    }

    /**
     * Convert the staff's mouse pitch axis into the native body frame.
     *
     * <p>Same-dimension straddle: mirrors the server's geometric goal chooser — when the
     * cursor works the body from the exit side of a ROTATED portal pair, the view axis maps
     * back through the portal rotation; translation-only pairs need (and get) nothing.
     *
     * <p>Cross-dimension: unchanged — post-transit frames rotate by the recorded crossing,
     * pre-transit far-side rotation uses the straddle mapping.
     */
    public static Vec3 unmapStaffInputAxis(Player player, ClientSubLevel sub, Vec3 axis) {
        TransitFrame transit = TRANSIT_FRAMES.get(sub.getUniqueId());
        if (transit != null && transit.bodyIsInTargetFrame(sub) && transit.playerIsInSourceFrame(player)) {
            org.joml.Vector3d mapped = transit.rotation().transform(
                new org.joml.Vector3d(axis.x, axis.y, axis.z));
            return new Vec3(mapped.x, mapped.y, mapped.z);
        }

        Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(sub);
        Portal session = IplStraddleSessionStore.resolvePortal(sub);
        if (session != null && parent == player.level()
            && session.getDestDim().equals(session.level().dimension())) {
            ipl.sable.transit.IplStraddlePoseMap.StraddleMapping mapping =
                ipl.sable.transit.IplStraddlePoseMap.StraddleMapping.of(session);
            if (mapping.isIdentityRotation()) return axis;
            return sameDimCursorMapped(player, sub, mapping) ? mapping.unmapVec(axis) : axis;
        }

        // Before a parent flip the real body remains in its source frame. A player can still
        // walk through the aperture and rotate the held construction from the destination side,
        // so convert that destination-world view axis back into the source body frame.
        ipl.sable.transit.IplStraddlePoseMap.StraddleMapping mapping =
            ipl.sable.transit.IplStraddlePoseMap.getMappingInto(sub, player.level());
        if (mapping == null) {
            mapping = ipl.sable.transit.IplStraddlePoseMap.getCollisionMappingInto(
                sub, player.level(), player.getBoundingBox());
        }
        if (mapping != null) return mapping.unmapVec(axis);
        return axis;
    }

    /** Client mirror of the server's same-dimension goal chooser, with its own stickiness. */
    private static boolean sameDimCursorMapped(
        Player player, ClientSubLevel sub,
        ipl.sable.transit.IplStraddlePoseMap.StraddleMapping mapping
    ) {
        double distance = 10.0;
        dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.ClientDragSession session =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
        if (session != null && session.dragSubLevel().getUniqueId().equals(sub.getUniqueId())) {
            distance = session.distance();
        }
        Vec3 goal = player.getEyePosition().add(player.getLookAngle().scale(distance));
        dev.ryanhcode.sable.companion.math.BoundingBox3dc body = sub.boundingBox();
        double rawDistance = distanceToBox(body, goal);
        double mappedDistance = distanceToBox(body, mapping.unmapPoint(goal));

        Boolean previous = SAME_DIM_AXIS_MAPPED.get(sub.getUniqueId());
        boolean useMapped;
        if (previous != null && previous) {
            useMapped = mappedDistance < rawDistance + SAME_DIM_SWITCH_MARGIN;
        } else {
            useMapped = mappedDistance + SAME_DIM_SWITCH_MARGIN < rawDistance;
        }
        SAME_DIM_AXIS_MAPPED.put(sub.getUniqueId(), useMapped);
        return useMapped;
    }

    private static double distanceToBox(
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box, Vec3 point
    ) {
        double x = Math.max(box.minX(), Math.min(box.maxX(), point.x));
        double y = Math.max(box.minY(), Math.min(box.maxY(), point.y));
        double z = Math.max(box.minZ(), Math.min(box.maxZ(), point.z));
        double dx = point.x - x, dy = point.y - y, dz = point.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------
    // Beam route builder inputs (geometry itself lives in IplStaffBeamRoutes).
    // ------------------------------------------------------------------

    /**
     * The recorded cross-dimension crossing portal for a body already in its target frame,
     * when the staff is still in the crossing's source world. Null otherwise.
     */
    @Nullable
    public static Portal transitRoutePortal(ClientSubLevel sub, Level staffLevel) {
        TransitFrame transit = TRANSIT_FRAMES.get(sub.getUniqueId());
        if (transit == null || !transit.bodyIsInTargetFrame(sub)
            || !transit.sourceDim().equals(staffLevel.dimension())) {
            return null;
        }
        return findPortal(transit.sourceDim(), transit.portalId());
    }

    /** Captured pick-time portal chain for this drag, when it starts in {@code staffLevel}. */
    @Nullable
    public static List<Portal> capturedPortals(UUID subId, Level staffLevel) {
        PortalTarget captured = DRAG_TARGETS.get(subId);
        if (captured == null || captured.portals().isEmpty()) return null;
        if (captured.portals().get(0).level() != staffLevel) return null;
        return captured.portals();
    }

    @Nullable
    private static Portal findPortal(ResourceKey<Level> levelKey, UUID portalId) {
        ClientLevel main = net.minecraft.client.Minecraft.getInstance().level;
        if (main != null && main.dimension().equals(levelKey)) {
            for (net.minecraft.world.entity.Entity entity : main.entitiesForRendering()) {
                if (entity instanceof Portal portal && !portal.isRemoved() && portal.getUUID().equals(portalId)) {
                    return portal;
                }
            }
            for (Portal portal : GlobalPortalStorage.getGlobalPortals(main)) {
                if (!portal.isRemoved() && portal.getUUID().equals(portalId)) return portal;
            }
        }
        for (ClientLevel level : qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds()) {
            if (level == main) continue;
            if (!level.dimension().equals(levelKey)) continue;
            for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
                if (entity instanceof Portal portal && !portal.isRemoved() && portal.getUUID().equals(portalId)) {
                    return portal;
                }
            }
            for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
                if (!portal.isRemoved() && portal.getUUID().equals(portalId)) return portal;
            }
        }
        return null;
    }

    /**
     * Pose of a captured remote target in current staff render pass. Remove only the portal
     * suffix not traversed by this render pass, so every recursive pass receives its target in
     * its own world frame.
     */
    public static Pose3d mapStaffRenderPose(ClientSubLevel sub, Pose3dc pose) {
        PortalTarget target = DRAG_TARGETS.get(sub.getUniqueId());
        if (target == null || target.sub() != sub || target.portals().isEmpty()) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.level.Level renderLevel = mc.level;
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                renderLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering
                    .getRenderingPortal().getDestinationWorld();
            }
            ipl.sable.transit.IplStraddlePoseMap.StraddleMapping mapping = renderLevel == null
                ? null
                : ipl.sable.transit.IplStraddlePoseMap.getMappingInto(sub, renderLevel);
            return mapping == null ? new Pose3d(pose) : mapping.mapPose(pose);
        }
        Pose3d mapped = new Pose3d(pose);
        if (target.fromParentProjection()) {
            net.minecraft.world.level.Level renderLevel = net.minecraft.client.Minecraft.getInstance().level;
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                renderLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering
                    .getRenderingPortal().getDestinationWorld();
            }
            Portal portal = target.portals().get(0);
            if (renderLevel != null && portal.getDestDim() == renderLevel.dimension()) {
                Vec3 position = portal.transformPoint(new Vec3(
                    mapped.position().x(), mapped.position().y(), mapped.position().z()
                ));
                qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
                mapped.position().set(position.x, position.y, position.z);
                mapped.orientation().set(new org.joml.Quaterniond(
                    rotation.x, rotation.y, rotation.z, rotation.w
                ).mul(mapped.orientation()));
            }
            return mapped;
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
            qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
            org.joml.Quaterniond inverse = new org.joml.Quaterniond(
                rotation.x, rotation.y, rotation.z, rotation.w
            ).invert();
            mapped.position().set(position.x, position.y, position.z);
            mapped.orientation().set(inverse.mul(mapped.orientation()));
        }
        return mapped;
    }

    /** Keep a local projected target in its body frame too, not only portal-traversed ones. */
    public static void rememberLocalProjection(Player player, HitResult hit) {
        if (!(player.level() instanceof ClientLevel level) || !(hit instanceof BlockHitResult blockHit)) return;
        ClientSubLevel sub = getHitSubLevel(level, blockHit);
        if (sub == null) return;

        if (ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) == level) {
            PENDING_TARGETS.remove(sub.getUniqueId());
            DRAG_TARGETS.remove(sub.getUniqueId());
            GRAB_DISTANCE.remove(sub.getUniqueId());
            return;
        }
        IplClientHostedLookup.StraddleProjection projection =
            IplClientHostedLookup.getStraddleProjectionFor(sub);
        if (projection != null && projection.portal().getDestDim() == level.dimension()) {
            // Distance to the image the player actually clicked, not the hidden native pose.
            double distance = player.getEyePosition().distanceTo(
                projection.mappedPose().transformPosition(blockHit.getLocation()));
            PENDING_TARGETS.put(sub.getUniqueId(), new PortalTarget(
                sub, level, blockHit, List.of(projection.portal()), true, distance
            ));
            GRAB_DISTANCE.put(sub.getUniqueId(), distance);
        }
    }

    @Nullable
    private static ClientSubLevel getHitSubLevel(net.minecraft.world.level.Level level, BlockHitResult hit) {
        // Every hosted plot lives in the dedicated sublevels container. `level` is the visible
        // source/destination world during portal recursion and has no plot ownership map; using
        // it here lost the clicked plot anchor and made the constraint fall back to ship center.
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return null;
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(
            net.minecraft.core.BlockPos.containing(hit.getLocation())
        );
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(chunk);
        dev.ryanhcode.sable.sublevel.SubLevel sub = plot != null ? plot.getSubLevel() : null;
        return sub instanceof ClientSubLevel clientSub ? clientSub : null;
    }

    public record PortalTarget(
        ClientSubLevel sub, net.minecraft.world.level.Level world, BlockHitResult hit,
        List<Portal> portals, boolean fromParentProjection, double visibleDistance
    ) {}

    private record TransitFrame(
        ResourceKey<Level> sourceDim, ResourceKey<Level> targetDim,
        Vec3 origin, Vec3 sourceNormal, org.joml.Quaterniond rotation, UUID portalId
    ) {
        boolean bodyIsInTargetFrame(ClientSubLevel sub) {
            Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(sub);
            return parent != null && targetDim.equals(parent.dimension());
        }

        boolean playerIsInSourceFrame(Player player) {
            if (!sourceDim.equals(targetDim)) return sourceDim.equals(player.level().dimension());
            return sourceNormal.dot(player.getEyePosition().subtract(origin)) >= 0.05;
        }
    }

    /** A projection hit: plot-coordinate BlockHitResult + ray distance² (frame-comparable). */
    public record ProjectionHit(BlockHitResult hit, double distSq) {}

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
        java.util.List<IplClientHostedLookup.StraddleProjection> projections =
            IplClientHostedLookup.getStraddleProjectionsInto(level);
        if (projections.isEmpty()) return null;

        Vec3 from = ctx.getFrom();
        Vec3 to = ctx.getTo();
        ipl.sable.mixin.client.IplClipContextAccessor access =
            (ipl.sable.mixin.client.IplClipContextAccessor) ctx;

        BlockHitResult best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (IplClientHostedLookup.StraddleProjection projection : projections) {
            Pose3dc mapped = projection.mappedPose();

            // The mapped image exists only past the destination plane (the projection's
            // own render clip): shorten the ray to that half-space so hits can't land on
            // the not-yet-through half, while a ray entering through the portal still
            // reaches the emerged part behind the plane crossing.
            Vec3 planePos = projection.destPlane().pos();
            Vec3 planeNormal = projection.destPlane().normal();
            double dFrom = from.subtract(planePos).dot(planeNormal);
            double dTo = to.subtract(planePos).dot(planeNormal);
            if (dFrom < 0.0 && dTo < 0.0) continue; // entirely on the phantom side
            Vec3 segFrom = from;
            Vec3 segTo = to;
            if (dFrom < 0.0) {
                segFrom = from.add(to.subtract(from).scale(dFrom / (dFrom - dTo)));
            } else if (dTo < 0.0) {
                segTo = from.add(to.subtract(from).scale(dFrom / (dFrom - dTo)));
            }

            // Distance frame anchor: the ORIGINAL ray origin, not the clipped segment
            // start — distSq must stay comparable with the stock hit's measurement.
            Vector3d localStart = mapped.transformPositionInverse(
                new Vector3d(from.x, from.y, from.z));
            Vector3d segStart = mapped.transformPositionInverse(
                new Vector3d(segFrom.x, segFrom.y, segFrom.z));
            Vector3d segStop = mapped.transformPositionInverse(
                new Vector3d(segTo.x, segTo.y, segTo.z));

            ClipContext localCtx = new ClipContext(
                new Vec3(segStart.x, segStart.y, segStart.z),
                new Vec3(segStop.x, segStop.y, segStop.z),
                access.ipl$getBlock(), access.ipl$getFluid(),
                access.ipl$getCollisionContext());
            // The ray is already in plot space — suppress Sable's sub-level projection
            // (its own recursion flag), leaving pure vanilla traversal whose block reads
            // resolve through the plot bridge.
            ((dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension) localCtx)
                .sable$setDoNotProject(true);

            BlockHitResult hit = level.clip(localCtx);

            if (hit.getType() == HitResult.Type.MISS) continue;

            double distSq = hit.getLocation().distanceToSqr(
                localStart.x, localStart.y, localStart.z);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = hit;
            }
        }
        return best != null ? new ProjectionHit(best, bestDistSq) : null;
    }
}
