package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Per-thread scratch state that lets the entity-vs-ship collision loop cut individual
 * collision boxes to the piece of the body the entity's frame owns, instead of keeping or
 * dropping whole blocks.
 *
 * <p><b>Why this exists.</b> {@code SubLevelEntityCollisionMixin} filters the candidate
 * {@code BlockPos} iterable, which is the only granularity a position predicate can
 * offer: a block is either physically present in this frame or it is not. With an oblique
 * portal (the 45-degree case) that quantises the physical boundary to the sub-level's block
 * lattice, so a ship that is only partly through the portal is physically cut along block
 * divisions rather than along the portal plane -- you can stand on, or fall through, up to
 * half a block of material that the render pass has already clipped away. The visual seam
 * and the collision seam disagree.
 *
 * <p><b>How.</b> The block filter is deliberately generous: it keeps a block whenever ANY
 * part of its cube may be in the piece. This class then trims what survives. Sable's
 * collision loop obtains each block's collision boxes through
 * {@code FastVoxelShapeIterable.sable$allBoxes()} and turns every box into an OBB; by
 * wrapping that iterator we hand back the box pieces that lie in the frame's
 * {@link IplCollisionRegion} (a box crossing a prism face becomes up to one piece per crossed
 * face), so the SAT solver sees the material that actually exists. The cut follows the
 * portal plane and the aperture's sides down to sub-block precision rather than the lattice.
 *
 * <p><b>Frame and lifecycle.</b> The region is plot-local, matching the coordinates the
 * collision loop works in before it applies the sub-level pose. {@link #setRegion} is
 * called once per sub-level, at the point the candidate block iterable is created, and
 * is explicitly cleared for non-straddling sub-levels so state can never leak from one
 * sub-level to the next. {@link #noteBlock} is fed by the same filtering iterator that
 * drives every loop over those blocks, so the box clip always knows which block the
 * boxes it is being handed belong to. Everything is {@link ThreadLocal}: client and
 * server collision run concurrently.
 *
 * <p>Boxes that lie entirely outside the piece are dropped from the iterator, which is
 * what makes a block that survived the (generous) position filter contribute nothing
 * when the piece happens to miss all of its actual collision geometry.
 */
public final class IplStraddleCollisionClip {

    private IplStraddleCollisionClip() {
    }

    private static final class State {
        @Nullable
        IplCollisionRegion region;
        int blockX;
        int blockY;
        int blockZ;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Installs (or with null, clears) the plot-local piece for the sub-level being tested. */
    public static void setRegion(@Nullable IplCollisionRegion region) {
        STATE.get().region = region == null || region.isUnbounded() ? null : region;
    }

    /** Records which block the boxes handed to {@link #clip} belong to. */
    public static void noteBlock(int x, int y, int z) {
        State state = STATE.get();
        state.blockX = x;
        state.blockY = y;
        state.blockZ = z;
    }

    public static boolean isActive() {
        return STATE.get().region != null;
    }

    /**
     * Wraps a block's collision-box iterator so every box is trimmed to the piece. Returns
     * the input untouched when no cut applies, so non-straddling ships pay nothing beyond
     * one thread-local read.
     */
    public static Iterator<BoundingBox3dc> clip(Iterator<BoundingBox3dc> boxes) {
        State state = STATE.get();
        IplCollisionRegion region = state.region;
        if (region == null) return boxes;
        return new ClippedIterator(boxes, region, state.blockX, state.blockY, state.blockZ);
    }

    private static final class ClippedIterator implements Iterator<BoundingBox3dc> {
        private final Iterator<BoundingBox3dc> in;
        private final IplCollisionRegion region;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final ArrayDeque<BoundingBox3dc> pending = new ArrayDeque<>(2);
        private final List<double[]> pieces = new ArrayList<>(2);

        ClippedIterator(Iterator<BoundingBox3dc> in, IplCollisionRegion region,
                        int blockX, int blockY, int blockZ) {
            this.in = in;
            this.region = region;
            this.offsetX = blockX;
            this.offsetY = blockY;
            this.offsetZ = blockZ;
        }

        @Override
        public boolean hasNext() {
            while (pending.isEmpty() && in.hasNext()) {
                clipOne(in.next());
            }
            return !pending.isEmpty();
        }

        @Override
        public BoundingBox3dc next() {
            if (!hasNext()) throw new NoSuchElementException();
            return pending.poll();
        }

        /**
         * Boxes arrive in block-local coordinates (0..1 within the block), so they are
         * lifted into plot-local space for the cut and pushed back afterwards -- the
         * caller adds the block position again itself.
         */
        private void clipOne(BoundingBox3dc box) {
            double[] local = {
                box.minX() + offsetX, box.minY() + offsetY, box.minZ() + offsetZ,
                box.maxX() + offsetX, box.maxY() + offsetY, box.maxZ() + offsetZ};
            pieces.clear();
            if (!region.clip(local, pieces)) {
                pending.add(box);
                return;
            }
            for (double[] piece : pieces) {
                pending.add(new BoundingBox3d(
                    piece[0] - offsetX, piece[1] - offsetY, piece[2] - offsetZ,
                    piece[3] - offsetX, piece[4] - offsetY, piece[5] - offsetZ));
            }
        }
    }
}
