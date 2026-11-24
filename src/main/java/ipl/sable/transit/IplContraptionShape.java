package ipl.sable.transit;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A kinematic contraption (a Create bearing, piston, pulley, gantry or cart structure) on a body,
 * in the body's plot frame at one moment: the body block it hangs on (its holder) and the centres
 * of its blocks.
 *
 * <p>Its blocks carry piece labels like the body's voxels ({@link IplPieceMap#contraptionLabels}):
 * a block changes piece only when its centre passes an aperture rectangle, front to back into the
 * child piece, back to front into the parent ({@link IplContraptionMaterial}). A block lying
 * across an aperture belongs to both pieces of the edge, each keeping its side of the plane.
 */
public final class IplContraptionShape {
    /** Half diagonal of a block: a block whose centre is this close to a plane may cross it. */
    static final double BLOCK_RADIUS = 0.8660254037844386;

    public final Entity entity;
    /** Centre of the body block the contraption hangs on. */
    public final double tx, ty, tz;
    /** Local block positions; {@link #centres} holds their plot centres in the same order. */
    public final BlockPos[] blocks;
    final double[] centres;
    /** {@code [cx, cy, cz, r]}: the sphere a rotating contraption sweeps; null for translating ones. */
    @Nullable final double[] sweep;

    public IplContraptionShape(Entity entity, double tx, double ty, double tz, BlockPos[] blocks, double[] centres,
                               @Nullable double[] sweep) {
        this.entity = entity;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.blocks = blocks;
        this.centres = centres;
        this.sweep = sweep;
    }

    public double x(int i) { return centres[i * 3]; }
    public double y(int i) { return centres[i * 3 + 1]; }
    public double z(int i) { return centres[i * 3 + 2]; }

    /** Local block positions as {@link BlockPos#asLong}, the keys of the contraption's labels. */
    public long[] packedBlocks() {
        long[] out = new long[blocks.length];
        for (int i = 0; i < out.length; i++) out[i] = blocks[i].asLong();
        return out;
    }

    /** Label of block {@code i}; {@code labels} null means every block is native. */
    public int label(@Nullable Long2ByteMap labels, int i) {
        return labels == null ? IplPieceMap.NATIVE : labels.get(blocks[i].asLong()) & 0xFF;
    }

    // ------------------------------------------------------------------ pieces

    /**
     * Where the contraption is at this moment: each block in the piece of its label and, when it
     * lies across the aperture of an edge of that piece, in the edge's other piece too. The cut
     * follows the plane from the first touch, whichever side the centre is on, so a block that
     * passes between two label samples is never missing on either side.
     */
    public Split split(IplPieceMap.Edge[] edges, @Nullable Long2ByteMap labels) {
        int[] pieces = new int[blocks.length];
        int[] across = new int[blocks.length];
        BitSet nodes = new BitSet();
        List<IplPieceMap.Edge> cut = new ArrayList<>(1);
        for (int i = 0; i < blocks.length; i++) {
            int piece = label(labels, i);
            pieces[i] = piece;
            across[i] = -1;
            nodes.set(piece);
            for (IplPieceMap.Edge e : edges) {
                if (e.parent != piece && e.child != piece || !liesAcross(e.aperture, i)) continue;
                across[i] = piece == e.parent ? e.child : e.parent;
                nodes.set(across[i]);
                if (!cut.contains(e)) cut.add(e);
                break;
            }
        }
        for (IplPieceMap.Edge e : edges) {
            if (nodes.get(e.parent) && nodes.get(e.child) && !cut.contains(e)) cut.add(e);
        }
        return new Split(pieces, across, nodes, cut);
    }

    /**
     * {@link #split} of one moment: block {@code i} is in {@code pieces[i]} and, when it lies across
     * an aperture, also in {@code across[i]} (else -1); {@code cut} holds the edges between pieces
     * the contraption is in.
     */
    public record Split(int[] pieces, int[] across, BitSet nodes, List<IplPieceMap.Edge> cut) {

        public boolean whollyIn(int node) {
            return nodes.cardinality() == 1 && nodes.get(node);
        }

        /** Whether block {@code i} is (partly) in piece {@code node}. */
        public boolean holds(int i, int node) {
            return pieces[i] == node || across[i] == node;
        }
    }

    /**
     * Pieces the contraption holds open: the pieces its blocks are in, and across one edge out of
     * them, the other side when its swept volume touches that edge's aperture (a turning part comes
     * back). Never transitive: holding a session cannot reach pieces the contraption is not near.
     */
    public BitSet held(IplPieceMap.Edge[] edges, @Nullable Long2ByteMap labels) {
        BitSet in = split(edges, labels).nodes();
        BitSet out = (BitSet) in.clone();
        if (sweep == null) return out;
        for (IplPieceMap.Edge e : edges) {
            boolean parent = in.get(e.parent), child = in.get(e.child);
            if (parent != child && touches(e.aperture)) out.set(parent ? e.child : e.parent);
        }
        return out;
    }

    /**
     * Whether a block of piece {@code node} lies across {@code aperture} (plot frame of that piece)
     * with its centre in front: the contraption is entering it, like a body voxel touching it.
     */
    public boolean entering(IplPieceMap.Aperture aperture, @Nullable Long2ByteMap labels, int node) {
        for (int i = 0; i < blocks.length; i++) {
            if (label(labels, i) == node && aperture.side(x(i), y(i), z(i)) >= 0.0 && liesAcross(aperture, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fraction of an observed sweep at which block {@code i} passes the aperture rectangle
     * ({@code margin} around it): front to back when {@code forward}, else back to front; NaN when
     * it does not. The block moves from {@code previous} (centres a tick earlier, same order) to its
     * centre now; the sweep covers the part {@code [from, to]} of that tick, in which the aperture
     * moves from {@code before} to {@code now}.
     */
    public double crossing(IplPieceMap.Aperture before, IplPieceMap.Aperture now, double[] previous,
                           double from, double to, int i, boolean forward, double margin) {
        double x0 = lerp(previous[i * 3], x(i), from), x1 = lerp(previous[i * 3], x(i), to);
        double y0 = lerp(previous[i * 3 + 1], y(i), from), y1 = lerp(previous[i * 3 + 1], y(i), to);
        double z0 = lerp(previous[i * 3 + 2], z(i), from), z1 = lerp(previous[i * 3 + 2], z(i), to);
        double s0 = before.side(x0, y0, z0), s1 = now.side(x1, y1, z1);
        if (forward ? s0 < 0.0 || s1 >= 0.0 : s0 >= 0.0 || s1 < 0.0) return Double.NaN;
        double t = s0 / (s0 - s1);
        IplPieceMap.Aperture at = IplApertureSweep.interpolate(before, now, t);
        double hx = lerp(x0, x1, t), hy = lerp(y0, y1, t), hz = lerp(z0, z1, t);
        return Math.abs(at.u(hx, hy, hz)) <= at.halfW() + margin
            && Math.abs(at.v(hx, hy, hz)) <= at.halfH() + margin ? t : Double.NaN;
    }

    /** Whether the contraption lies across {@code aperture}'s plane over its rectangle, or may swing there. */
    public boolean touches(IplPieceMap.Aperture a) {
        if (sweep != null) {
            double r = sweep[3];
            return Math.abs(a.side(sweep[0], sweep[1], sweep[2])) <= r
                && Math.abs(a.u(sweep[0], sweep[1], sweep[2])) <= a.halfW() + r
                && Math.abs(a.v(sweep[0], sweep[1], sweep[2])) <= a.halfH() + r;
        }
        for (int i = 0; i < blocks.length; i++) if (liesAcross(a, i)) return true;
        return false;
    }

    /** Whether block {@code i} may cross the aperture's plane within its rectangle (either way). */
    private boolean liesAcross(IplPieceMap.Aperture a, int i) {
        return Math.abs(a.side(x(i), y(i), z(i))) <= BLOCK_RADIUS && a.laterallyInside(x(i), y(i), z(i), BLOCK_RADIUS);
    }

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]} of everything the contraption covers or sweeps. */
    public double[] bounds() {
        if (sweep != null) {
            double r = sweep[3];
            return new double[]{sweep[0] - r, sweep[1] - r, sweep[2] - r, sweep[0] + r, sweep[1] + r, sweep[2] + r};
        }
        double[] b = {tx, ty, tz, tx, ty, tz};
        for (int i = 0; i < blocks.length; i++) {
            b[0] = Math.min(b[0], x(i)); b[1] = Math.min(b[1], y(i)); b[2] = Math.min(b[2], z(i));
            b[3] = Math.max(b[3], x(i)); b[4] = Math.max(b[4], y(i)); b[5] = Math.max(b[5], z(i));
        }
        for (int i = 0; i < 3; i++) {
            b[i] -= BLOCK_RADIUS;
            b[i + 3] += BLOCK_RADIUS;
        }
        return b;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
