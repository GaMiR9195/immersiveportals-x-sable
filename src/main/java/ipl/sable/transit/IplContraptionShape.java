package ipl.sable.transit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A kinematic contraption (a Create bearing, piston, pulley, gantry or cart structure) on a body,
 * in the body's plot frame at one moment: the body block it hangs on (its tether) and the
 * centres of its blocks.
 *
 * <p>A contraption is rigid geometry carried by its body and never has labels of its own. Its
 * pieces follow the rope rule from the tether: the segment from the tether to a point enters an
 * edge's child piece where it passes the aperture front to back and returns to the parent where
 * it passes back (natives: {@code IplPieces::classify_tethered}). So the contraption lives on the
 * body's projections, cut where it really passes an opening. The body flips when its blocks are
 * through; a part still behind the exit is then walked through the exit's reverse opening.
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

    // ------------------------------------------------------------------ pieces

    /** Piece of block {@code i}, walked from a tether of piece {@code tetherPiece}. */
    public int pieceOf(IplPieceMap.Edge[] edges, int tetherPiece, int i) {
        return walk(edges, tetherPiece, tx, ty, tz, x(i), y(i), z(i), null);
    }

    /**
     * Where the contraption is at this moment. A block lying across an edge's aperture belongs to
     * both pieces of the edge, each keeping its side of the plane, like a body voxel in the
     * aperture zone: the cut follows the plane from the first touch, not from the block centre.
     */
    public Split split(IplPieceMap.Edge[] edges, int tetherPiece) {
        int[] pieces = new int[blocks.length];
        int[] across = new int[blocks.length];
        BitSet nodes = new BitSet();
        List<IplPieceMap.Edge> cut = new ArrayList<>(1);
        for (int i = 0; i < blocks.length; i++) {
            int piece = pieceOf(edges, tetherPiece, i);
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
     * Pieces the contraption is in or may swing into: its blocks' pieces, then both sides of every
     * edge its swept volume touches, transitively. A session it holds stays open.
     */
    public BitSet reached(IplPieceMap.Edge[] edges, int tetherPiece) {
        BitSet out = new BitSet();
        out.set(tetherPiece);
        for (int i = 0; i < blocks.length; i++) out.set(pieceOf(edges, tetherPiece, i));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (IplPieceMap.Edge e : edges) {
                boolean parent = out.get(e.parent), child = out.get(e.child);
                if (parent == child || !touches(e.aperture)) continue;
                out.set(parent ? e.child : e.parent);
                grew = true;
            }
        }
        return out;
    }

    /**
     * Whether the part of the contraption in piece {@code node} reaches {@code aperture} (plot frame
     * of {@code node}): a block passed it on its way from where its segment entered {@code node},
     * or the contraption touches it.
     */
    public boolean reaches(IplPieceMap.Edge[] edges, int tetherPiece, int node, IplPieceMap.Aperture aperture) {
        if (!reached(edges, tetherPiece).get(node)) return false;
        if (touches(aperture)) return true;
        double[] entry = new double[3];
        for (int i = 0; i < blocks.length; i++) {
            if (walk(edges, tetherPiece, tx, ty, tz, x(i), y(i), z(i), entry) != node) continue;
            if (passes(aperture, entry[0], entry[1], entry[2], x(i), y(i), z(i)) >= 0.0) return true;
        }
        return false;
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

    /** Whether block {@code i} may cross the aperture's plane within its rectangle. */
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

    // ------------------------------------------------------------------ walk

    /**
     * Piece reached by the segment from {@code a} (piece {@code piece}) to {@code p} through the
     * edges, crossings in segment order. {@code entry}, if given, receives the point where the
     * segment entered that piece ({@code a} when it never left the first one).
     */
    public static int walk(IplPieceMap.Edge[] edges, int piece, double ax, double ay, double az,
                           double px, double py, double pz, double @Nullable [] entry) {
        if (entry != null) {
            entry[0] = ax;
            entry[1] = ay;
            entry[2] = az;
        }
        double after = -1.0;
        for (int pass = 0; pass < edges.length; pass++) {
            IplPieceMap.Edge next = null;
            double first = Double.POSITIVE_INFINITY;
            for (IplPieceMap.Edge e : edges) {
                if (piece != e.parent && piece != e.child) continue;
                double t = piece == e.parent
                    ? passes(e.aperture, ax, ay, az, px, py, pz)
                    : passes(e.aperture, px, py, pz, ax, ay, az);
                if (t < 0.0) continue;
                if (piece == e.child) t = 1.0 - t;
                if (t > after && t < first) {
                    first = t;
                    next = e;
                }
            }
            if (next == null) break;
            piece = piece == next.parent ? next.child : next.parent;
            after = first;
            if (entry != null) {
                entry[0] = ax + (px - ax) * first;
                entry[1] = ay + (py - ay) * first;
                entry[2] = az + (pz - az) * first;
            }
        }
        return piece;
    }

    /**
     * Fraction of the segment from {@code a} to {@code b} where it passes the aperture from its
     * front ({@code side >= 0}) to its back inside the rectangle; -1 when it does not.
     */
    static double passes(IplPieceMap.Aperture aperture, double ax, double ay, double az,
                         double bx, double by, double bz) {
        double s0 = aperture.side(ax, ay, az), s1 = aperture.side(bx, by, bz);
        if (s0 < 0.0 || s1 >= 0.0) return -1.0;
        double t = s0 / (s0 - s1);
        double hx = ax + (bx - ax) * t, hy = ay + (by - ay) * t, hz = az + (bz - az) * t;
        return aperture.laterallyInside(hx, hy, hz, 0.0) ? t : -1.0;
    }
}
