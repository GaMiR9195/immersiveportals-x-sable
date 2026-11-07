package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.voxel_shape_iteration.FastVoxelShapeIterable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import ipl.sable.transit.IplStraddleCollisionClip;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;

/**
 * Столкновения сущностей с кораблём, пересекающим портал: сущность сталкивается с одним куском
 * (по меткам), блоки зоны апертуры подрезаются её плоскостью, а куски за порталом сталкиваются
 * в своей (отображённой) позе.
 */
@Pseudo
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionMixin {

    // ---- Срез по кускам ----
    // Entity-vs-ship collision while the ship straddles a portal: the entity collides with the
    // ONE piece of the frame chosen for it (IplStraddlePoseMap.chooseCollisionFrame, the same
    // choice the pose wraps below make). The candidate block iterable is filtered to that
    // piece's blocks -- the same iterable feeds the main SAT loop and the tracking check, and
    // the step-up probe reuses the installed cut for its own candidates -- and the boxes of the
    // blocks an aperture zone splits are trimmed at the aperture plane
    // (IplStraddleCollisionClip). Material beside the portal frame keeps its piece, so a ring
    // pushed through a portal stays solid where it went past the frame.
    //
    // <p>Non-straddling ships install no cut and pass through unchanged.

    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"
        )
    )
    private static Iterable<BlockPos> ipl$keepPieceBlocks(
        BlockPos min, BlockPos max, Operation<Iterable<BlockPos>> original,
        @Local(argsOnly = true) Entity entity,
        @Local(ordinal = 1) SubLevel subLevel
    ) {
        // Installed (or cleared) for EVERY sub-level of the intersection loop, so a cut can
        // never leak into the next sub-level's boxes.
        IplStraddleCollisionClip.install(IplStraddlePoseMap.getBlockCollisionCut(
            subLevel, entity.level(), entity.getBoundingBox()));
        return IplStraddleCollisionClip.filter(original.call(min, max));
    }

    @WrapOperation(
        method = "tryStepUp",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"
        )
    )
    private static Iterable<BlockPos> ipl$keepPieceStepBlocks(
        BlockPos min, BlockPos max, Operation<Iterable<BlockPos>> original
    ) {
        return IplStraddleCollisionClip.filter(original.call(min, max));
    }

    @Inject(method = "collide", at = @At("RETURN"))
    private static void ipl$clearPieceCut(CallbackInfoReturnable<?> cir) {
        IplStraddleCollisionClip.clear();
    }

    /**
     * Trims the boxes of split blocks at the aperture plane. Every box query of this class
     * (movement solver, step-up probe, suffocation/darkness shape) goes through
     * {@code sable$allBoxes}, so all of them see the same cut.
     */
    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/mixinterface/voxel_shape_iteration/FastVoxelShapeIterable;sable$allBoxes()Ljava/util/Iterator;"
        )
    )
    private static Iterator<BoundingBox3dc> ipl$clipSplitBlockBoxes(
        FastVoxelShapeIterable shape, Operation<Iterator<BoundingBox3dc>> original
    ) {
        return IplStraddleCollisionClip.clip(original.call(shape));
    }

    // ---- Поза части корабля за порталом ----
    // Entity collision against the THROUGH-PORTAL portion of a straddling sub-level.
    //
    // <p>{@code SubLevelEntityCollision.collide} gathers candidates via
    // {@code Sable.HELPER.getAllIntersecting} (which includes foreign straddlers with
    // portal-MAPPED bounds — see {@code CompanionIntersectMixin}) and then reads
    // {@code logicalPose()}/{@code lastPose()} directly. For a ship whose parent is ANOTHER
    // dimension straddling into the entity's dimension, those poses are in the wrong frame —
    // this wrap substitutes the portal-mapped poses, so an entity on the dest side stands on /
    // collides with the through-part exactly where it renders. Ship-block reads at plot-grid
    // coordinates work from any dimension via the plot bridge.
    //
    // <p>Native-frame ships get a null offset and pass through unchanged. When the flip fires,
    // the parent becomes the entity's dimension, the offset vanishes, and the handoff is
    // seamless.

    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;"
        )
    )
    private static Pose3d ipl$mapLogicalPoseForForeignStraddler(
        SubLevel sub, Operation<Pose3d> original,
        @Local(argsOnly = true) Entity entity
    ) {
        Pose3d pose = original.call(sub);
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            sub, entity.level(), entity.getBoundingBox());
        return mapping != null ? mapping.mapPose(pose) : pose;
    }

    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;lastPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"
        )
    )
    private static Pose3dc ipl$mapLastPoseForForeignStraddler(
        SubLevel sub, Operation<Pose3dc> original,
        @Local(argsOnly = true) Entity entity
    ) {
        Pose3dc pose = original.call(sub);
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            sub, entity.level(), entity.getBoundingBox());
        return mapping != null ? mapping.mapPose(pose) : pose;
    }
}
