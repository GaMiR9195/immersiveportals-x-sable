package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

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
