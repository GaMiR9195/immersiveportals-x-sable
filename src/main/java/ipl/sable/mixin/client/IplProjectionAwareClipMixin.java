package ipl.sable.mixin.client;

import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.client.IplStraddleStaffPick;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Projection-aware raycasting: vanilla {@code clip} on the client also hits the
 * THROUGH-PORTAL part of straddling hosted sub-levels.
 *
 * <p>Sable's raycast overlay (mixed into {@code BlockGetter.clip}) transforms rays into
 * each intersecting sub-level's local space using the level pose provider — unmapped for a
 * ship straddling in from another dimension, so its through-part is invisible to every
 * vanilla raycast (crosshair targeting, block place/break, the physics staff). This merged
 * override extends the result: after the stock clip (vanilla + Sable's overlay, reached
 * via {@code BlockGetter.super.clip}), the ray is also clipped against each straddle
 * projection with the MAPPED pose, and the closest hit wins.
 *
 * <p>Frame-correct distance comparison: a sub-level hit's location is in PLOT coordinates,
 * so its ray distance is measured from the ray origin transformed into the SAME frame
 * (the owner's unmapped pose for stock hits — matching what Sable used — and the mapped
 * pose for projection hits). Rigid transforms preserve distance, so the comparison is
 * valid across frames.
 *
 * <p>Merged-override pattern (spec §20.0 item 8): Sable's overlay lives in the interface
 * default's bytecode, so {@code BlockGetter.super.clip} includes it; injection into the
 * merged handlers themselves is impossible. Re-entrancy (projection clips call
 * {@code level.clip}) is guarded by a thread-local depth flag.
 */
@Mixin(value = ClientLevel.class, priority = 1200)
public abstract class IplProjectionAwareClipMixin implements BlockGetter {

    private static final ThreadLocal<Boolean> IPL$REENTRANT = ThreadLocal.withInitial(() -> false);

    @Override
    public BlockHitResult clip(ClipContext ctx) {
        BlockHitResult base = BlockGetter.super.clip(ctx);

        if (!IplDimAgnostic.isEnabled() || IPL$REENTRANT.get()) {
            return base;
        }

        ClientLevel self = (ClientLevel) (Object) this;
        IPL$REENTRANT.set(true);
        try {
            IplStraddleStaffPick.ProjectionHit projectionHit =
                IplStraddleStaffPick.clipProjections(self, ctx);

            // TEMPORARY diagnostic (2s throttle)
            long now = System.currentTimeMillis();
            if (now - ipl$lastProbeMs > 2000) {
                ipl$lastProbeMs = now;
                java.util.List<ipl.sable.client.IplClientHostedLookup.StraddleProjection> projections =
                    ipl.sable.client.IplClientHostedLookup.getStraddleProjectionsInto(self);
                StringBuilder hosted = new StringBuilder();
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer hc =
                    ipl.sable.client.IplClientHostedLookup.getHostingContainerOrNull();
                if (hc != null) {
                    for (dev.ryanhcode.sable.sublevel.SubLevel sub : hc.getAllSubLevels()) {
                        if (sub.isRemoved()) continue;
                        net.minecraft.world.level.Level parent =
                            ((ipl.sable.duck.IplSubLevelDuck) sub).ipl$getParentLevel();
                        Object decision = sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel cs
                            ? ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlane(cs)
                            : "notClient";
                        hosted.append(String.format("[uuid=%.8s parent=%s renderData=%s decision=%s] ",
                            sub.getUniqueId().toString(),
                            parent == null ? "null" : parent.dimension().location().getPath(),
                            sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel c && c.getRenderData() != null,
                            decision != null ? "yes" : "null"));
                    }
                }
                org.slf4j.LoggerFactory.getLogger("ipl-clip").info(
                    "[IPL-CLIP] dim={} base={}@{} projHit={} projCount={} hosted={}",
                    self.dimension().location(),
                    base == null ? null : base.getType(),
                    base == null ? null : base.getLocation(),
                    projectionHit != null ? projectionHit.hit().getLocation() : null,
                    projections.size(), hosted);
            }

            if (projectionHit == null) {
                return base;
            }

            if (base == null || base.getType() == HitResult.Type.MISS) {
                return projectionHit.hit();
            }

            double baseDistSq = ipl$frameDistanceSq(self, ctx.getFrom(), base.getLocation());
            return projectionHit.distSq() < baseDistSq ? projectionHit.hit() : base;
        } finally {
            IPL$REENTRANT.set(false);
        }
    }

    private static long ipl$lastProbeMs = 0L;

    /**
     * Ray distance² for a stock clip hit. World-frame hits measure directly; plot-frame
     * hits (Sable sub-level hits, coords in the millions) measure from the ray origin
     * inverse-transformed by the owner's pose — the same (unmapped) pose Sable's overlay
     * used to produce the hit.
     */
    private static double ipl$frameDistanceSq(ClientLevel level, Vec3 from, Vec3 hitLoc) {
        if (Math.abs(hitLoc.x) < 1_000_000 && Math.abs(hitLoc.z) < 1_000_000) {
            return from.distanceToSqr(hitLoc);
        }

        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                (net.minecraft.world.level.Level) level);
        if (container != null) {
            LevelPlot plot = container.getPlot(
                net.minecraft.util.Mth.floor(hitLoc.x) >> 4,
                net.minecraft.util.Mth.floor(hitLoc.z) >> 4);
            SubLevel owner = plot != null ? plot.getSubLevel() : null;
            if (owner != null) {
                dev.ryanhcode.sable.companion.math.Pose3dc pose;
                if ((Object) level instanceof LevelPoseProviderExtension ext) {
                    pose = ext.sable$getPose(owner);
                } else {
                    pose = owner.logicalPose();
                }
                Vector3d localFrom = pose.transformPositionInverse(
                    new Vector3d(from.x, from.y, from.z));
                return hitLoc.distanceToSqr(localFrom.x, localFrom.y, localFrom.z);
            }
        }
        // Unknown frame; treat as far so a known-good projection hit wins.
        return Double.MAX_VALUE;
    }
}
