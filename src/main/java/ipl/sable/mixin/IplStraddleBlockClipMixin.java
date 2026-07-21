package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * Aperture clip for ENTITY-vs-ship collision on straddling hosted sub-levels — the
 * gameplay-collision counterpart of the native solver contact clip (spec §2.5).
 *
 * <p>{@code SubLevelEntityCollision.collide} SATs the entity's OBB against every ship
 * block near it, oblivious to the portal: from the source side an entity kept colliding
 * with the through-part (rendered and physically present dest-side only), and via the
 * mapped image it would collide with the not-yet-through part. This wrap filters the
 * candidate block iterable so blocks on the wrong side of the portal plane (inside the
 * aperture column) simply don't exist for collision — the same iterable feeds the main
 * SAT loop, step-up probing, and the tracking check, so all stay consistent.
 *
 * <p>Non-straddling ships get a null filter and pass through unchanged. The lookahead in
 * the filtering iterator advances the underlying mutable BlockPos at the same point
 * vanilla's own {@code betweenClosed} iterator does (inside hasNext), so reuse semantics
 * are identical.
 */
@Pseudo
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class IplStraddleBlockClipMixin {

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
        if (keep == null) return all;
        return () -> new FilteredIterator(all.iterator(), keep);
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
            return next;
        }
    }
}
