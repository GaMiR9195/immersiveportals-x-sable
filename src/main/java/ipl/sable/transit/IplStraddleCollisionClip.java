package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Per-thread state of the entity-vs-ship collision cut ({@code SubLevelEntityCollisionMixin}).
 *
 * <p>Sable's collision loop walks the candidate blocks of one sub-level and tests the entity
 * against each block's boxes ({@code FastVoxelShapeIterable.sable$allBoxes()}). While the body
 * straddles a portal the entity collides with ONE piece ({@link IplStraddlePoseMap.PieceCut}):
 * the candidate blocks are filtered to that piece ({@link #filter}), and the boxes of the few
 * blocks an aperture zone splits between two pieces are trimmed at the aperture plane
 * ({@link #clip}), so the seam follows the portal to sub-block precision while everything else
 * is decided by labels alone.
 *
 * <p>The cut is installed for each sub-level where its candidate iterable is created and
 * cleared when {@code collide} returns; the filtering iterator notes the block whose boxes are
 * fetched next, so the box clip always knows which plane (if any) applies. Everything is
 * {@link ThreadLocal}: client and server collide concurrently.
 */
public final class IplStraddleCollisionClip {

    private IplStraddleCollisionClip() {
    }

    private static final class State {
        @Nullable IplStraddlePoseMap.PieceCut cut;
        /** Plane of the block noted last; null = its boxes stay whole. */
        @Nullable IplStraddlePoseMap.LocalHalfSpace plane;
        int blockX;
        int blockY;
        int blockZ;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Installs (or with null, clears) the cut for the sub-level being tested. */
    public static void install(@Nullable IplStraddlePoseMap.PieceCut cut) {
        State state = STATE.get();
        state.cut = cut;
        state.plane = null;
    }

    public static void clear() {
        install(null);
    }

    /** Whether a cut is installed (step-up probes reuse it for their own candidate blocks). */
    public static boolean hasCut() {
        return STATE.get().cut != null;
    }

    /** Whether the boxes fetched next belong to a block split at an aperture plane. */
    public static boolean isActive() {
        return STATE.get().plane != null;
    }

    /**
     * {@code all} restricted to the installed piece; every block handed out is noted for
     * {@link #clip}. Lookahead happens inside {@code hasNext}, the same timing as vanilla's
     * {@code betweenClosed} iterator, so its MutableBlockPos reuse semantics are preserved.
     */
    public static Iterable<BlockPos> filter(Iterable<BlockPos> all) {
        State state = STATE.get();
        IplStraddlePoseMap.PieceCut cut = state.cut;
        if (cut == null) return all;
        return () -> new Iterator<>() {
            private final Iterator<BlockPos> in = all.iterator();
            private BlockPos next;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                while (!hasNext && in.hasNext()) {
                    BlockPos candidate = in.next();
                    if (cut.keeps(candidate)) {
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
                state.blockX = next.getX();
                state.blockY = next.getY();
                state.blockZ = next.getZ();
                state.plane = cut.planeFor(state.blockX, state.blockY, state.blockZ);
                return next;
            }
        };
    }

    /**
     * Wraps the collision boxes of the block noted last so each is trimmed at its aperture
     * plane. Returns the input untouched for whole blocks and when no cut applies.
     */
    public static Iterator<BoundingBox3dc> clip(Iterator<BoundingBox3dc> boxes) {
        State state = STATE.get();
        IplStraddlePoseMap.LocalHalfSpace plane = state.plane;
        if (plane == null) return boxes;
        return new ClippedIterator(boxes, plane, state.blockX, state.blockY, state.blockZ);
    }

    private static final class ClippedIterator implements Iterator<BoundingBox3dc> {
        private final Iterator<BoundingBox3dc> in;
        private final IplStraddlePoseMap.LocalHalfSpace plane;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        @Nullable
        private BoundingBox3dc next;

        ClippedIterator(Iterator<BoundingBox3dc> in, IplStraddlePoseMap.LocalHalfSpace plane,
                        int blockX, int blockY, int blockZ) {
            this.in = in;
            this.plane = plane;
            this.offsetX = blockX;
            this.offsetY = blockY;
            this.offsetZ = blockZ;
        }

        @Override
        public boolean hasNext() {
            while (next == null && in.hasNext()) {
                next = clipOne(in.next());
            }
            return next != null;
        }

        @Override
        public BoundingBox3dc next() {
            if (!hasNext()) throw new NoSuchElementException();
            BoundingBox3dc result = next;
            next = null;
            return result;
        }

        /**
         * Boxes arrive in block-local coordinates (0..1 within the block), so they are lifted
         * into plot-local space for the cut and pushed back afterwards -- the caller adds the
         * block position again itself.
         */
        @Nullable
        private BoundingBox3dc clipOne(BoundingBox3dc box) {
            double minX = box.minX() + offsetX;
            double minY = box.minY() + offsetY;
            double minZ = box.minZ() + offsetZ;
            double maxX = box.maxX() + offsetX;
            double maxY = box.maxY() + offsetY;
            double maxZ = box.maxZ() + offsetZ;
            BoundingBox3d cut = IplStraddlePoseMap.clipLocalBoxKeeping(minX, minY, minZ, maxX, maxY, maxZ, plane);
            if (cut == null) return null;
            if (cut.minX == minX && cut.minY == minY && cut.minZ == minZ
                && cut.maxX == maxX && cut.maxY == maxY && cut.maxZ == maxZ) {
                return box;
            }
            // A degenerate sliver is worse than nothing: SAT on a zero-thickness box yields a
            // zero MTV that still counts as colliding, pinning the entity against a surface
            // that has no material left.
            if (cut.maxX - cut.minX <= 1.0e-6 || cut.maxY - cut.minY <= 1.0e-6 || cut.maxZ - cut.minZ <= 1.0e-6) {
                return null;
            }
            return new BoundingBox3d(
                cut.minX - offsetX, cut.minY - offsetY, cut.minZ - offsetZ,
                cut.maxX - offsetX, cut.maxY - offsetY, cut.maxZ - offsetZ);
        }
    }
}
