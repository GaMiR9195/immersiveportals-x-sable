package ipl.sable.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Convex CELLS of a straddle piece, for the shader's independent clip slots.
 *
 * <p>Кусок тела (родной или за порталом) — пересечение призм пути минус призмы дочерних
 * порталов и старших соседей, в общем случае невыпуклое множество. Аппаратный срез оставляет
 * только пересечение полупространств, поэтому кусок раскладывается на выпуклые ячейки и каждая
 * непустая ячейка рисуется своим проходом со своими плоскостями. Пустота ячеек и лишние
 * плоскости решаются точно, перебором вершин внутри коробки плота, так что обычный случай
 * (корабль целиком проходит в проём) остаётся одной ячейкой с одной плоскостью.
 *
 * <p>A plane is {@code {a, b, c, d}} with a unit normal, kept where
 * {@code a x + b y + c z + d >= 0}; a cell is the intersection of its planes. Everything is
 * evaluated inside a BOX (the plot's oriented bounding box as six planes): geometry never
 * leaves it, so a cell without interior in the box is never drawn and a plane that cuts
 * nothing in the box is dropped. Callers should pass coordinates near the box (for example
 * relative to its centre) to keep the vertex arithmetic well conditioned.
 */
public final class IplClipCells {

    /** Cells thinner than this (blocks) have no interior: a zero-volume seam is never a pass. */
    static final double SLIVER = 1.0e-4;
    private static final double TOLERANCE = 1.0e-7;
    private static final double SINGULAR = 1.0e-9;

    private IplClipCells() {}

    /**
     * The six planes of the box {@code center ± e0 ± e1 ± e2} ({@code halfEdges} = the three
     * half-edge vectors), grown by {@code margin} on every face.
     */
    public static double[][] box(double[] center, double[][] halfEdges, double margin) {
        double[][] out = new double[6][];
        for (int k = 0; k < 3; k++) {
            double[] e = halfEdges[k];
            double[] n = cross(halfEdges[(k + 1) % 3], halfEdges[(k + 2) % 3]);
            double len = Math.sqrt(dot(n, n));
            if (len < SINGULAR) {
                n = e.clone();
                len = Math.sqrt(dot(n, n));
            }
            if (len < SINGULAR) {
                n = new double[3];
                n[k] = 1.0;
                len = 1.0;
            }
            n[0] /= len;
            n[1] /= len;
            n[2] /= len;
            double extent = dot(n, e);
            if (extent < 0.0) {
                n[0] = -n[0];
                n[1] = -n[1];
                n[2] = -n[2];
                extent = -extent;
            }
            double nc = dot(n, center);
            out[2 * k] = new double[]{-n[0], -n[1], -n[2], nc + extent + margin};
            out[2 * k + 1] = new double[]{n[0], n[1], n[2], -nc + extent + margin};
        }
        return out;
    }

    /** The other closed half-space of {@code plane}. */
    public static double[] flip(double[] plane) {
        return new double[]{-plane[0], -plane[1], -plane[2], -plane[3]};
    }

    /** Whether the intersection of {@code planes} and {@code box} has an interior point. */
    public static boolean hasInterior(List<double[]> planes, double[][] box) {
        double[][] all = new double[planes.size() + box.length][];
        for (int i = 0; i < planes.size(); i++) {
            double[] p = planes.get(i);
            all[i] = new double[]{p[0], p[1], p[2], p[3] - SLIVER};
        }
        System.arraycopy(box, 0, all, planes.size(), box.length);
        return hasVertex(all);
    }

    /**
     * {@code cells} minus the convex set {@code convex} (its planes in order) as cells with
     * interior: a cell {@code c} that meets the set becomes the pieces
     * {@code c ∩ convex[0..i-1] ∩ ¬convex[i]}; a cell that misses it stays as it is.
     */
    public static List<List<double[]>> subtract(List<List<double[]>> cells, double[][] convex, double[][] box) {
        List<List<double[]>> out = new ArrayList<>(cells.size() + 1);
        for (List<double[]> cell : cells) {
            List<double[]> prefix = new ArrayList<>(cell.size() + convex.length);
            prefix.addAll(cell);
            for (double[] plane : convex) prefix.add(plane);
            if (!hasInterior(prefix, box)) {
                out.add(cell);
                continue;
            }
            prefix.subList(cell.size(), prefix.size()).clear();
            for (double[] plane : convex) {
                List<double[]> piece = new ArrayList<>(prefix.size() + 1);
                piece.addAll(prefix);
                piece.add(flip(plane));
                if (hasInterior(piece, box)) out.add(piece);
                prefix.add(plane);
            }
        }
        return out;
    }

    /** {@code cell} without the planes that cut nothing inside {@code box}; later planes go first. */
    public static List<double[]> essential(List<double[]> cell, double[][] box) {
        List<double[]> kept = new ArrayList<>(cell);
        for (int i = kept.size() - 1; i >= 0; i--) {
            List<double[]> cut = new ArrayList<>(kept.size());
            for (int j = 0; j < kept.size(); j++) {
                if (j != i) cut.add(kept.get(j));
            }
            cut.add(flip(kept.get(i)));
            if (!hasInterior(cut, box)) kept.remove(i);
        }
        return kept;
    }

    /** Whether some vertex of the (bounded) polyhedron {@code planes} satisfies all of them. */
    private static boolean hasVertex(double[][] planes) {
        int n = planes.length;
        double[] x = new double[3];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    if (solve(planes[i], planes[j], planes[k], x) && inside(planes, x)) return true;
                }
            }
        }
        return false;
    }

    /** The point on all three planes (Cramer's rule); false when they share no single point. */
    private static boolean solve(double[] p, double[] q, double[] r, double[] x) {
        double c0 = q[1] * r[2] - q[2] * r[1];
        double c1 = q[2] * r[0] - q[0] * r[2];
        double c2 = q[0] * r[1] - q[1] * r[0];
        double det = p[0] * c0 + p[1] * c1 + p[2] * c2;
        if (Math.abs(det) < SINGULAR) return false;
        // Solve a·x = -d for the three planes.
        double bp = -p[3], bq = -q[3], br = -r[3];
        double[] rxp = {r[1] * p[2] - r[2] * p[1], r[2] * p[0] - r[0] * p[2], r[0] * p[1] - r[1] * p[0]};
        double[] pxq = {p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]};
        x[0] = (bp * c0 + bq * rxp[0] + br * pxq[0]) / det;
        x[1] = (bp * c1 + bq * rxp[1] + br * pxq[1]) / det;
        x[2] = (bp * c2 + bq * rxp[2] + br * pxq[2]) / det;
        return true;
    }

    private static boolean inside(double[][] planes, double[] x) {
        for (double[] p : planes) {
            if (p[0] * x[0] + p[1] * x[1] + p[2] * x[2] + p[3] < -TOLERANCE) return false;
        }
        return true;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
