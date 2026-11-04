package ipl.sable.transit;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The material one collision frame of a straddling body owns: inside every {@code keep}
 * plane and outside every {@code exclude} prism (each prism = the intersection of its faces).
 * Planes are {@code {a, b, c, d}} with a unit normal, kept where {@code a x + b y + c z + d >= 0}.
 *
 * <p>Не полупространство, а кусок дерева проекций: родной кадр — всё вне призм корневых
 * порталов (часть, ушедшая за плоскость мимо рамки, остаётся твёрдой), кадр узла — внутри
 * призм всего пути, вне призм дочерних порталов и старших соседей. Это тот же кусок, что у
 * физики (natives), отсечения рендера ({@code IplClipCells}) и
 * {@link IplProjectionTree#pieceAt}.
 *
 * <p>Pure geometry on double arrays: a generous whole-block test for the candidate filter and
 * a box clip for the individual collision boxes. A box minus a convex prism is not a box, so
 * the clip returns pieces, {@code box ∩ {f <= 0}} for every face {@code f} the box crosses;
 * their union is exactly {@code box − prism}. Axis-aligned planes cut exactly, oblique ones
 * with ImmersivePortals' {@code CollisionHelper.clipBox} convention ({@link #clipKeeping}).
 */
public final class IplCollisionRegion {

    /** Tolerance of the inside/outside tests: pose round-off must not leave slivers behind. */
    static final double EPS = 1.0e-7;
    /**
     * Piece budget per collision box: a piece is only split while the total stays below it,
     * beyond that it is kept whole (generous), so a box never yields more than
     * {@code MAX_PIECES + 4} pieces.
     */
    static final int MAX_PIECES = 8;
    /**
     * A trimmed box this thin along any axis is dropped: SAT on a zero-thickness box yields a
     * zero MTV that still counts as colliding and pins the entity to material that is gone.
     */
    static final double SLIVER = 1.0e-6;

    private final List<double[]> keep;
    private final List<double[][]> exclude;

    public IplCollisionRegion(List<double[]> keep, List<double[][]> exclude) {
        this.keep = List.copyOf(keep);
        this.exclude = List.copyOf(exclude);
    }

    /** No plane at all: the whole body. */
    public boolean isUnbounded() {
        return keep.isEmpty() && exclude.isEmpty();
    }

    /** The same region with every plane mapped by {@code planeMap} (a change of frame). */
    public IplCollisionRegion map(UnaryOperator<double[]> planeMap) {
        List<double[]> k = new ArrayList<>(keep.size());
        for (double[] plane : keep) k.add(planeMap.apply(plane));
        List<double[][]> e = new ArrayList<>(exclude.size());
        for (double[][] prism : exclude) {
            double[][] faces = new double[prism.length][];
            for (int i = 0; i < prism.length; i++) faces[i] = planeMap.apply(prism[i]);
            e.add(faces);
        }
        return new IplCollisionRegion(k, e);
    }

    /**
     * Whether any part of the axis-aligned cube {@code center ± half} may lie in the region:
     * false only when the cube is entirely outside a keep plane or entirely inside an
     * excluded prism (generous, the box clip trims the rest).
     */
    public boolean keepsCube(double cx, double cy, double cz, double half) {
        for (double[] plane : keep) {
            if (eval(plane, cx, cy, cz) + half * spread(plane) < -EPS) return false;
        }
        for (double[][] prism : exclude) {
            boolean inside = true;
            for (double[] face : prism) {
                if (eval(face, cx, cy, cz) - half * spread(face) < -EPS) {
                    inside = false;
                    break;
                }
            }
            if (inside) return false;
        }
        return true;
    }

    /**
     * Clips {@code box} ({@code {minX, minY, minZ, maxX, maxY, maxZ}}) to the region: returns
     * false when the box lies in the region untouched, otherwise appends the surviving pieces
     * (possibly none) to {@code out} and returns true.
     */
    public boolean clip(double[] box, List<double[]> out) {
        double[] b = box;
        for (double[] plane : keep) {
            b = clipKeeping(b, plane);
            if (b == null) return true;
        }
        List<double[]> pieces = new ArrayList<>(2);
        pieces.add(b);
        for (double[][] prism : exclude) {
            List<double[]> next = new ArrayList<>(pieces.size() + 1);
            for (int i = 0; i < pieces.size(); i++) {
                subtract(pieces.get(i), prism, next, next.size() + pieces.size() - i < MAX_PIECES);
            }
            pieces = next;
            if (pieces.isEmpty()) return true;
        }
        if (pieces.size() == 1 && pieces.get(0) == box) return false;
        for (double[] piece : pieces) {
            if (piece[3] - piece[0] > SLIVER && piece[4] - piece[1] > SLIVER && piece[5] - piece[2] > SLIVER) {
                out.add(piece);
            }
        }
        return true;
    }

    /** Squared distance from a point to the part of {@code box} in the region; MAX_VALUE if none. */
    public double distanceSq(double[] box, double x, double y, double z) {
        List<double[]> pieces = new ArrayList<>(2);
        if (!clip(box, pieces)) return distanceSqToBox(box, x, y, z);
        double best = Double.MAX_VALUE;
        for (double[] piece : pieces) best = Math.min(best, distanceSqToBox(piece, x, y, z));
        return best;
    }

    /** {@code b − prism} as pieces appended to {@code out}; without {@code split}, {@code b} whole. */
    private static void subtract(double[] b, double[][] prism, List<double[]> out, boolean split) {
        boolean inside = true;
        for (double[] face : prism) {
            if (max(b, face) <= EPS) {
                out.add(b); // entirely outside this face, so outside the prism
                return;
            }
            if (min(b, face) < -EPS) inside = false;
        }
        if (inside) return;
        if (!split) {
            out.add(b);
            return;
        }
        for (double[] face : prism) {
            if (min(b, face) >= -EPS) continue; // the whole box is on the prism side of this face
            double[] piece = clipKeeping(b, new double[]{-face[0], -face[1], -face[2], -face[3]});
            if (piece != null) out.add(piece);
        }
    }

    /**
     * {@code b} cut to the kept side of {@code plane}; {@code b} itself when it lies there
     * entirely, null when nothing of it does. Axis-aligned planes cut exactly; for an oblique
     * plane the behind-most corner is pushed onto the plane along the normal (ImmersivePortals'
     * {@code CollisionHelper.clipBox} convention, the one its own portal collision uses).
     */
    @Nullable
    static double[] clipKeeping(double[] b, double[] plane) {
        double a = plane[0], bb = plane[1], c = plane[2];
        double px = a > 0 ? b[0] : b[3], py = bb > 0 ? b[1] : b[4], pz = c > 0 ? b[2] : b[5];
        double sx = a > 0 ? b[3] : b[0], sy = bb > 0 ? b[4] : b[1], sz = c > 0 ? b[5] : b[2];
        double pushed = a * px + bb * py + c * pz + plane[3];
        if (pushed >= -EPS) return b;
        double kept = a * sx + bb * sy + c * sz + plane[3];
        if (kept <= EPS) return null;
        px -= pushed * a;
        py -= pushed * bb;
        pz -= pushed * c;
        return new double[]{
            Math.min(px, sx), Math.min(py, sy), Math.min(pz, sz),
            Math.max(px, sx), Math.max(py, sy), Math.max(pz, sz)};
    }

    private static double eval(double[] plane, double x, double y, double z) {
        return plane[0] * x + plane[1] * y + plane[2] * z + plane[3];
    }

    /** Support of the unit-half-extent cube along the plane normal. */
    private static double spread(double[] plane) {
        return Math.abs(plane[0]) + Math.abs(plane[1]) + Math.abs(plane[2]);
    }

    private static double min(double[] b, double[] plane) {
        return plane[0] * (plane[0] > 0 ? b[0] : b[3])
            + plane[1] * (plane[1] > 0 ? b[1] : b[4])
            + plane[2] * (plane[2] > 0 ? b[2] : b[5]) + plane[3];
    }

    private static double max(double[] b, double[] plane) {
        return plane[0] * (plane[0] > 0 ? b[3] : b[0])
            + plane[1] * (plane[1] > 0 ? b[4] : b[1])
            + plane[2] * (plane[2] > 0 ? b[5] : b[2]) + plane[3];
    }

    private static double distanceSqToBox(double[] b, double x, double y, double z) {
        double dx = x - Math.max(b[0], Math.min(b[3], x));
        double dy = y - Math.max(b[1], Math.min(b[4], y));
        double dz = z - Math.max(b[2], Math.min(b[5], z));
        return dx * dx + dy * dy + dz * dz;
    }
}
