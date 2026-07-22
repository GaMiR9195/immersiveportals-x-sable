package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Frame-correct {@code getOnPos} for riders of straddling ships — MUST run BEFORE Sable's
 * {@code sable$preGetOnPos} (priority 1100). Callbacks at the same injection point execute
 * in mixin application order, and Sable's branch CANCELS, so a later-applied handler never
 * sees the broken cases. Hence priority 1000 in a class separate from our other
 * Entity hooks (which need priority 1200 to apply after Sable's getInBlockState overwrite).
 *
 * <p>Why: Sable's handler computes the rider's standing block in PLOT-LOCAL coordinates via
 * the UNMAPPED pose — for a foreign straddler the local Y is off by the portal offset
 * (~264 instead of ~128 here). Vanilla travel feeds that into {@code Level.hasChunkAt},
 * whose first gate is {@code isOutsideBuildHeight}: in the nether (0..256) the bad Y reads
 * as "chunk below not loaded", and travel pins dM.y to {@code -0.1 * 0.98F} every tick —
 * eaten jumps and the floaty glide on the through-part. It also breaks ground friction
 * (ice not slippery) since the friction block is sampled at the same position.
 *
 * <p>Covers both rider states: the tracked field set, and the field transiently cleared
 * (IP's portal collision adjusts Y by an epsilon each tick, which makes Sable null it)
 * while still standing on the mapped deck.
 */
@Mixin(value = Entity.class, priority = 1000)
public abstract class IplStraddleOnPosMixin {

    // NOTE: an earlier HEAD-inject here computed the mapped position directly inside
    // getOnPos(F). It stacked with the caller-side correction below (double offset
    // subtraction → y ≈ -10 → "unloaded" → the -0.098 pin returned for AIRBORNE entities
    // near the ship). Removed: for airborne entities vanilla's real-world answer is
    // correct, and for tracked riders Sable's unmapped value is corrected by the
    // modifier below.

    /**
     * Caller-side frame correction — the path of last resort that actually works. All
     * doors on {@code getOnPos(F)} itself are locked: Sable's cancellable handler runs
     * first regardless of our priority, its setReturnValue is invisible to return-value
     * modifiers on that method, and mixin-added handler bodies cannot be wrapped. But the
     * vanilla CALLERS (travel's friction/loaded-check position, the fall-on block, the
     * standing block) return normally — so we correct the frame there: if the returned
     * position is plot-bound and the plot's owner is straddling into this entity's
     * dimension, subtract the portal offset.
     */
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(
        method = {
            "getBlockPosBelowThatAffectsMyMovement",
            "getOnPosLegacy",
            "getOnPos()Lnet/minecraft/core/BlockPos;"
        },
        at = @At("RETURN"),
        require = 0
    )
    private BlockPos ipl$correctOnPosFrame(BlockPos original) {
        Entity self = (Entity) (Object) this;
        if (!ipl.sable.dim.IplDimAgnostic.isEnabled()) return original;

        // Cheap reject: plot-grid coords are in the millions; world coords are not.
        if (Math.abs(original.getX()) < 1_000_000 && Math.abs(original.getZ()) < 1_000_000) {
            return original;
        }

        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container = ipl$hostingContainer(self);
        if (container == null) return original;

        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot =
            container.getPlot(original.getX() >> 4, original.getZ() >> 4);
        if (plot == null) return original;
        SubLevel owner = plot.getSubLevel();
        if (owner == null) return original;

        BlockPos offset = IplStraddlePoseMap.getOffsetInto(owner, self.level());
        if (offset == null) return original;

        // The unmapped-frame local position is off by exactly the portal offset.
        return original.offset(-offset.getX(), -offset.getY(), -offset.getZ());
    }

    @org.spongepowered.asm.mixin.Unique
    private static dev.ryanhcode.sable.api.sublevel.SubLevelContainer ipl$hostingContainer(Entity self) {
        if (self.level().isClientSide) {
            return ipl.sable.client.IplClientHostedLookup.getHostingContainerOrNull();
        }
        if (self.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.server.level.ServerLevel hosting =
                ipl.sable.dim.SableSubLevelDimension.getSableSubLevelsOrNull(serverLevel.getServer());
            return hosting == null ? null
                : dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                    (net.minecraft.world.level.Level) hosting);
        }
        return null;
    }
}
