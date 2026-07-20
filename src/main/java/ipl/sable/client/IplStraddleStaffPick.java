package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;

import com.mojang.datafixers.util.Pair;
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
 */
public final class IplStraddleStaffPick {

    /** Selected portal path per ship. Drag keeps this frame after the player looks away. */
    private static final Map<UUID, PortalTarget> PORTAL_TARGETS = new HashMap<>();

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
            player, player.level(), eye, player.getViewVector(partialTick), range, List.of(), 0
        );
        if (target == null) return null;
        PORTAL_TARGETS.put(target.sub().getUniqueId(), target);
        return target;
    }

    /** Portal recursion with Sable plot hits compared in their visible world frame. */
    @Nullable
    private static PortalTarget pickThroughPortals(
        Player player, Level level, Vec3 from, Vec3 direction, double remaining,
        List<Portal> path, int depth
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
            return portalTargetForHit(sub, level, block, path);
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
            depth + 1
        );
    }

    private static PortalTarget portalTargetForHit(
        ClientSubLevel sub, Level level, BlockHitResult hit, List<Portal> path
    ) {
        return new PortalTarget(sub, level, hit, path, false);
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

    /** Current render path lets beam vertices cross the same portal recursion as terrain. */
    public static List<Portal> getActiveRenderPath() {
        if (!qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            return List.of();
        }
        return qouteall.imm_ptl.core.render.context_management.PortalRendering.getPortalPath();
    }

    /** Save portal path only after the staff actually starts dragging this selected ship. */
    public static void beginDrag(ClientSubLevel sub) {
        PortalTarget selected = PORTAL_TARGETS.get(sub.getUniqueId());
        if (selected == null || selected.sub() != sub) {
            PORTAL_TARGETS.remove(sub.getUniqueId());
            return;
        }
        PORTAL_TARGETS.put(sub.getUniqueId(), selected);
    }

    public static void clearDragTargets() {
        PORTAL_TARGETS.clear();
    }

    /** Parent handoff marks body as native in destination frame; packets become raw again. */
    public static void onTransitHandoff(UUID subId) {
        PORTAL_TARGETS.remove(subId);
    }

    /**
     * Convert Simulated's outgoing relative drag vector before packet construction. The stock
     * packet has no portal/frame field and server reconstructs its goal as `serverEye + vector`.
     * Through-portal picks encode target-frame points only until parent handoff. Once the body
     * completes transit, packets become raw again and server state maps direct-grab goals late.
     */
    public static Vector3d mapOutgoingDragGoal(Player player, UUID subId, org.joml.Vector3dc relativeGoal) {
        PortalTarget target = PORTAL_TARGETS.get(subId);
        if (target == null || target.portals().isEmpty()) {
            return new Vector3d(relativeGoal);
        }

        Vec3 eye = player.getEyePosition();
        Vec3 mapped = eye.add(relativeGoal.x(), relativeGoal.y(), relativeGoal.z());
        if (target.fromParentProjection()) {
            for (int i = target.portals().size() - 1; i >= 0; i--) {
                mapped = target.portals().get(i).inverseTransformPoint(mapped);
            }
        } else {
            for (Portal portal : target.portals()) {
                mapped = portal.transformPoint(mapped);
            }
        }
        return new Vector3d(mapped.x - eye.x, mapped.y - eye.y, mapped.z - eye.z);
    }

    /**
     * Resolve the visible route of a staff beam for clients which did not perform the original
     * pick. Server drag packets only contain a plot anchor, so use the player's aim and that
     * anchor to select the portal path in exactly the same frame as the held staff.
     */
    public static List<Portal> getBeamPortalPath(Level sourceLevel, Vec3 source, ClientSubLevel sub, Vec3 localAnchor) {
        PortalTarget captured = PORTAL_TARGETS.get(sub.getUniqueId());
        if (captured != null && captured.sub() == sub && !captured.portals().isEmpty()) {
            // A straddle projection is already in the player's world. Its portal is only used
            // to encode the motor goal back into the body's parent frame, never as a visual ray.
            return captured.fromParentProjection() ? List.of() : captured.portals();
        }

        Level targetLevel = ipl.sable.dim.IplDimAgnostic.getParentLevel(sub);
        if (targetLevel == null) return List.of();
        Vec3 target = sub.renderPose().transformPosition(localAnchor);
        List<Portal> prefix = getActiveRenderPath();
        IplClientHostedLookup.StraddleProjection projection = IplClientHostedLookup.getStraddleProjectionFor(sub);
        if (projection != null && sourceLevel.dimension() == projection.portal().level().dimension()) {
            Vec3 projectedTarget = projection.mappedPose().transformPosition(localAnchor);
            if (projection.portal().rayTrace(source, projectedTarget) != null) {
                return List.of(projection.portal());
            }
        }
        if (sourceLevel.dimension() == targetLevel.dimension()) return prefix;
        PathSearch search = new PathSearch(target, source.distanceToSqr(target));
        findBeamPath(sourceLevel, source, targetLevel.dimension(), new java.util.ArrayList<>(),
            new java.util.HashSet<>(), 0, search);
        if (search.portals == null) return prefix;
        if (prefix.isEmpty()) return search.portals;
        java.util.ArrayList<Portal> fullPath = new java.util.ArrayList<>(prefix.size() + search.portals.size());
        fullPath.addAll(prefix);
        fullPath.addAll(search.portals);
        return List.copyOf(fullPath);
    }

    /** Map a plot anchor into the coordinate frame of the active recursive portal render. */
    public static Vec3 mapBeamEndpoint(
        ClientSubLevel sub, Vec3 localAnchor, float partialTick, List<Portal> portals
    ) {
        net.minecraft.world.level.Level renderLevel = net.minecraft.client.Minecraft.getInstance().level;
        if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            renderLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering
                .getRenderingPortal().getDestinationWorld();
        }
        IplClientHostedLookup.StraddleProjection projection = IplClientHostedLookup.getStraddleProjectionFor(sub);
        Vec3 endpoint = projection != null && renderLevel != null
            && projection.portal().getDestDim() == renderLevel.dimension()
            ? projection.mappedPose().transformPosition(localAnchor)
            : sub.renderPose(partialTick).transformPosition(localAnchor);
        List<Portal> renderPath = getActiveRenderPath();
        int traversed = 0;
        while (traversed < renderPath.size() && traversed < portals.size()
            && renderPath.get(traversed) == portals.get(traversed)) {
            traversed++;
        }
        for (int i = portals.size() - 1; i >= traversed; i--) {
            endpoint = portals.get(i).inverseTransformPoint(endpoint);
        }
        return endpoint;
    }

    private static void findBeamPath(
        Level level, Vec3 source, net.minecraft.resources.ResourceKey<Level> targetDim,
        java.util.ArrayList<Portal> path, java.util.Set<UUID> visited, int depth, PathSearch best
    ) {
        if (depth >= 3) return;
        for (Portal portal : qouteall.imm_ptl.core.McHelper.getEntitiesNearby(level, source, Portal.class, 192.0)) {
            if (!visited.add(portal.getUUID())) continue;
            path.add(portal);
            if (portal.getDestDim().equals(targetDim)) {
                Vec3 sourceTarget = inverseThrough(path, best.target);
                // A route is usable only when its first finite aperture lies on the beam ray.
                Vec3 hit = path.get(0).rayTrace(source, sourceTarget);
                double score = source.distanceToSqr(sourceTarget);
                if (hit != null && score < best.score) {
                    best.score = score;
                    best.portals = List.copyOf(path);
                }
            } else if (portal.getDestinationWorld() instanceof ClientLevel destination) {
                findBeamPath(destination, portal.transformPoint(source), targetDim, path, visited, depth + 1, best);
            }
            path.remove(path.size() - 1);
            visited.remove(portal.getUUID());
        }
    }

    private static Vec3 inverseThrough(List<Portal> portals, Vec3 point) {
        for (int i = portals.size() - 1; i >= 0; i--) {
            point = portals.get(i).inverseTransformPoint(point);
        }
        return point;
    }

    private static final class PathSearch {
        final Vec3 target;
        List<Portal> portals;
        double score;

        PathSearch(Vec3 target, double score) {
            this.target = target;
            this.score = score;
        }
    }

    /**
     * Pose of a captured remote target in current staff render pass. Remove only the portal
     * suffix not traversed by this render pass, so every recursive pass receives its target in
     * its own world frame.
     */
    public static Pose3d mapStaffRenderPose(ClientSubLevel sub, Pose3dc pose) {
        PortalTarget target = PORTAL_TARGETS.get(sub.getUniqueId());
        if (target == null || target.sub() != sub || target.portals().isEmpty()) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.level.Level renderLevel = mc.level;
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                renderLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering
                    .getRenderingPortal().getDestinationWorld();
            }
            net.minecraft.core.BlockPos offset = renderLevel == null ? null
                : ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, renderLevel);
            return offset == null ? new Pose3d(pose)
                : ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset);
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
            PORTAL_TARGETS.remove(sub.getUniqueId());
            return;
        }
        IplClientHostedLookup.StraddleProjection projection =
            IplClientHostedLookup.getStraddleProjectionFor(sub);
        if (projection != null && projection.portal().getDestDim() == level.dimension()) {
            PORTAL_TARGETS.put(sub.getUniqueId(), new PortalTarget(
                sub, level, blockHit, List.of(projection.portal()), true
            ));
        }
    }

    @Nullable
    private static ClientSubLevel getHitSubLevel(net.minecraft.world.level.Level level, BlockHitResult hit) {
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
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
        List<Portal> portals, boolean fromParentProjection
    ) {}

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

            Vector3d localStart = mapped.transformPositionInverse(
                new Vector3d(from.x, from.y, from.z));
            Vector3d localEnd = mapped.transformPositionInverse(
                new Vector3d(to.x, to.y, to.z));

            ClipContext localCtx = new ClipContext(
                new Vec3(localStart.x, localStart.y, localStart.z),
                new Vec3(localEnd.x, localEnd.y, localEnd.z),
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
