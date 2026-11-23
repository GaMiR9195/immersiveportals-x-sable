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
 * body's projections, cut where it really passes an opening, and flips with its body once it is
 * through too. A part swinging back behind the exit later is walked through the exit's reverse
 * opening.
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
                if (e.parent != piece && e.child != piece || !liesAcross(e.aperture, i, piece == e.parent)) continue;
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
    public BitSet held(IplPieceMap.Edge[] edges, int tetherPiece) {
        BitSet in = split(edges, tetherPiece).nodes();
        in.set(tetherPiece);
        BitSet out = (BitSet) in.clone();
        if (sweep == null) return out;
        for (IplPieceMap.Edge e : edges) {
            boolean parent = in.get(e.parent), child = in.get(e.child);
            if (parent != child && touches(e.aperture)) out.set(parent ? e.child : e.parent);
        }
        return out;
    }

    /**
     * Whether the contraption enters {@code now} (an aperture in the plot frame of piece
     * {@code node}, {@code before} the same one a tick earlier) from that piece: a block of
     * {@code node} lies across it with its centre in front, or passed it front to back since
     * {@code previous} (block centres one tick earlier, same order; null when unknown) while staying
     * in {@code node}. Only blocks really at the opening count, like body voxels: the swept volume
     * never opens a session, and an aperture that coincides with the plane a block already passed
     * into {@code node} (the far face of the exit) is never entered.
     */
    public boolean enters(IplPieceMap.Edge[] edges, int tetherPiece, int node, IplPieceMap.Aperture before,
                          IplPieceMap.Aperture now, double @Nullable [] previous) {
        boolean moved = previous != null && previous.length == centres.length;
        for (int i = 0; i < blocks.length; i++) {
            boolean across = liesAcross(now, i, true);
            if (!across && !(moved && crossed(before, now, previous, i))) continue;
            if (pieceOf(edges, tetherPiece, i) != node) continue;
            if (!across && walk(edges, tetherPiece, tx, ty, tz,
                previous[i * 3], previous[i * 3 + 1], previous[i * 3 + 2], null) != node) continue;
            return true;
        }
        return false;
    }

    /** Whether block {@code i} went from the front of {@code before} to the back of {@code now} over the opening. */
    private boolean crossed(IplPieceMap.Aperture before, IplPieceMap.Aperture now, double[] previous, int i) {
        double px = previous[i * 3], py = previous[i * 3 + 1], pz = previous[i * 3 + 2];
        double s0 = before.side(px, py, pz), s1 = now.side(x(i), y(i), z(i));
        if (s0 < 0.0 || s1 >= 0.0) return false;
        double t = s0 / (s0 - s1);
        double hx = px + (x(i) - px) * t, hy = py + (y(i) - py) * t, hz = pz + (z(i) - pz) * t;
        return now.laterallyInside(hx, hy, hz, BLOCK_RADIUS) || before.laterallyInside(hx, hy, hz, BLOCK_RADIUS);
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

    /**
     * Whether block {@code i} lies across the aperture with its centre on the {@code front} side
     * (it is entering) or on the back (it is leaving).
     */
    private boolean liesAcross(IplPieceMap.Aperture a, int i, boolean front) {
        double side = a.side(x(i), y(i), z(i));
        return (front ? side >= 0.0 : side < 0.0) && liesAcross(a, i);
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
