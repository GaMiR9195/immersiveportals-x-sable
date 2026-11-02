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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Столкновения сущностей с кораблём, пересекающим портал: блоки режутся по плоскости портала,
 * а часть за порталом сталкивается в своей (отображённой) позе.
 */
@Pseudo
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionMixin {

    // ---- Отсечение блоков по плоскости портала ----
    // Portal-plane clip for ENTITY-vs-ship collision on straddling sub-levels — the
    // gameplay-collision counterpart of the native solver contact clip (spec §2.5).
    //
    // <p>{@code SubLevelEntityCollision.collide} SATs the entity's OBB against every ship
    // block near it, oblivious to the portal: from the source side an entity kept colliding
    // with the through-part (rendered and physically present dest-side only), and via the
    // mapped image it would collide with the not-yet-through part. This wrap filters the
    // candidate block iterable so blocks on the wrong side of the portal plane simply don't
    // exist for collision — the same iterable feeds the main
    // SAT loop, step-up probing, and the tracking check, so all stay consistent.
    //
    // <p>Non-straddling ships get a null filter and pass through unchanged. The lookahead in
    // the filtering iterator advances the underlying mutable BlockPos at the same point
    // vanilla's own {@code betweenClosed} iterator does (inside hasNext), so reuse semantics
    // are identical.
    //
    // <p><b>Sub-block precision.</b> The position filter alone can only work in whole
    // blocks, so the physical boundary used to land on the sub-level's block lattice instead
    // of on the portal plane: a ship that was only partly through the portal got cut along
    // block divisions, leaving up to half a block of phantom material behind the plane (the
    // "one block past the portal" you could stand on) or a matching hole in front of it. The
    // filter is therefore deliberately generous — it keeps a block if ANY part of its cube
    // is still on the kept side — and the second wrap below trims what survives, clipping
    // each individual collision box against the same half-space via
    // {@link IplStraddleCollisionClip}. Both loops that consume these boxes
    // ({@code collide} and the step-up probe's {@code hasCollision}) go through
    // {@code sable$allBoxes}, so the two stay consistent with each other and with the
    // position filter.

    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"
        ),
        require = 0
    )
    private static Iterable<BlockPos> ipl$dropWrongHalfBlocks(
        BlockPos min, BlockPos max, Operation<Iterable<BlockPos>> original,
        @Local(argsOnly = true) Entity entity,
        @Local(ordinal = 1) SubLevel subLevel
    ) {
        Iterable<BlockPos> all = original.call(min, max);
        Predicate<BlockPos> keep = IplStraddlePoseMap.getBlockCollisionKeepFilter(
            subLevel, entity.level(), entity.getBoundingBox());
        // Installed (or cleared) for EVERY sub-level in the intersection loop, not just
        // straddling ones, so a cut can never leak into the next sub-level's boxes.
        IplStraddleCollisionClip.setPlanes(
            keep == null ? null : IplStraddlePoseMap.getBlockCollisionKeepPlanes(
                subLevel, entity.level(), entity.getBoundingBox()));
        if (keep == null) return all;
        return () -> new FilteredIterator(all.iterator(), keep);
    }

    /**
     * Trims each surviving block's collision boxes at the portal plane. Applies to the
     * main SAT loop and to the step-up probe, both of which iterate boxes through this
     * same interface call.
     */
    @WrapOperation(
        // WIDENED: the cut must apply to EVERY box query Sable makes against a sub-level,
        // not just the movement solver. `getSubLevelEntityCollisionShape` feeds suffocation
        // and in-block darkness, and `tryStepUp` feeds the pose solver -- those two are why
        // the player still went dark and got forced into a crouch/prone pose behind the
        // portal plane while the movement collision itself was already correctly cut.
        // WIDENED TO EVERYTHING. A per-method whitelist kept missing call sites (step-up,
        // suffocation/darkness shape, fall probes), and each miss showed up as the player
        // being crushed, darkened or forced prone by geometry that is supposed to be cut
        // away behind the portal plane. There is no call site in this class where an
        // UNCLIPPED sub-level box is the correct answer while a cut is installed, so the
        // wrapper now applies to all of them.
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/mixinterface/voxel_shape_iteration/FastVoxelShapeIterable;sable$allBoxes()Ljava/util/Iterator;"
        ),
        require = 0
    )
    private static Iterator<BoundingBox3dc> ipl$clipBoxesAtPortalPlane(
        FastVoxelShapeIterable shape, Operation<Iterator<BoundingBox3dc>> original
    ) {
        return IplStraddleCollisionClip.clip(original.call(shape));
    }

    private static final class FilteredIterator implements Iterator<BlockPos> {
        private final Iterator<BlockPos> in;
        private final Predicate<BlockPos> keep;
        private BlockPos next;
        private boolean hasNext;

        FilteredIterator(Iterator<BlockPos> in, Predicate<BlockPos> keep) {
            this.in = in;
            this.keep = keep;
        }

        @Override
        public boolean hasNext() {
            while (!hasNext && in.hasNext()) {
                BlockPos candidate = in.next();
                if (keep.test(candidate)) {
                    next = candidate;
                    hasNext = true;
                }
            }
            return hasNext;
        }

        @Override
        public BlockPos next() {
            if (!hasNext()) throw new NoSuchElementException();
            hasNext = false;
            // Every loop over the candidate blocks pulls them through here, so this is
            // the one place that always knows which block the boxes fetched next belong
            // to — no fragile capture of the loop variable required.
            IplStraddleCollisionClip.noteBlock(next.getX(), next.getY(), next.getZ());
            return next;
        }
    }

    // ---- Поза части корабля за порталом ----
    // Entity collision against the THROUGH-PORTAL portion of a straddling sub-level.
    //
    // <p>{@code SubLevelEntityCollision.collide} gathers candidates via
    // {@code Sable.HELPER.getAllIntersecting} (which now includes foreign straddlers with
    // portal-MAPPED bounds — see {@code IplHostedIntersectionMixin}) and then reads
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
        ),
        require = 0
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
        ),
        require = 0
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
