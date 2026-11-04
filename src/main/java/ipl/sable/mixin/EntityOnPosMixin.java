package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.atlas.IplScenes;
import ipl.sable.client.IplClientProjections;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

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
public abstract class EntityOnPosMixin {

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
    @ModifyReturnValue(
        method = {
            "getBlockPosBelowThatAffectsMyMovement",
            "getOnPosLegacy",
            "getOnPos()Lnet/minecraft/core/BlockPos;"
        },
        at = @At("RETURN")
    )
    private BlockPos ipl$correctOnPosFrame(BlockPos original) {
        Entity self = (Entity) (Object) this;
        SubLevel owner = ipl$plotOwner(self, original);
        if (owner == null) return original;

        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            owner, self.level(), self.getBoundingBox());
        if (mapping == null) return original;

        // The handler computed L = poseInv(W) with the UNMAPPED pose, where W is the
        // rider's actual (mapped-frame) world position. The true plot position is
        // mappedPoseInv(W) = poseInv(mapInv(W)), so round-trip the returned block
        // center: L -> pose -> W -> unmap -> poseInv. Exact under rotation — the old
        // shortcut (subtract the world portal delta) was only right for translation
        // pairs, and its wrong Y on rotated crossings tripped travel's
        // "chunk below not loaded" pin (dM.y = -0.098/tick: the eaten-jump bug).
        Pose3dc pose = owner.logicalPose();
        Vector3d world = pose.transformPosition(new Vector3d(
            original.getX() + 0.5, original.getY() + 0.5, original.getZ() + 0.5));
        Vec3 unmapped = mapping.unmapPoint(
            new Vec3(world.x, world.y, world.z));
        Vector3d localTrue = pose.transformPositionInverse(
            new Vector3d(unmapped.x, unmapped.y, unmapped.z));
        return BlockPos.containing(localTrue.x, localTrue.y, localTrue.z);
    }

    /** The ship owning plot block {@code pos} in ANY level (a projected ship lives in another one). */
    @Unique
    @Nullable
    private static SubLevel ipl$plotOwner(Entity self, BlockPos pos) {
        if (self.level().isClientSide) {
            return IplClientProjections.findSubLevelAtPlot(
                Vec3.atCenterOf(pos));
        }
        return self.level() instanceof ServerLevel serverLevel
            ? IplScenes.plotOwner(serverLevel, pos) : null;
    }
}
