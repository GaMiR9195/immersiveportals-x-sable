package ipl.sable.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** IplClipCells: exact emptiness, partition of a box minus convex sets, redundant planes. */
public final class ClipCellsTest {
    private static int assertions;
    private static final double[] ORIGIN = {0, 0, 0};

    public static void main(String[] args) {
        double[][] cube = IplClipCells.box(new double[]{0, 0, 0},
            new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, 0.0);

        check(IplClipCells.hasInterior(List.of(new double[]{1, 0, 0, 0}), cube), "half cube has interior");
        check(!IplClipCells.hasInterior(List.of(new double[]{1, 0, 0, -1.5}), cube), "plane beyond the box");
        check(!IplClipCells.hasInterior(List.of(new double[]{1, 0, 0, 0}, new double[]{-1, 0, 0, 0}), cube),
            "opposed coincident cuts leave only a seam");
        check(IplClipCells.hasInterior(List.of(new double[]{1, 0, 0, 0.1}, new double[]{-1, 0, 0, 0.1}), cube),
            "thin slab is kept");

        // Prism through the cube's middle: x >= 0, |y| <= 0.5, |z| <= 0.5.
        double[][] prism = {{1, 0, 0, 0}, {0, -1, 0, 0.5}, {0, 1, 0, 0.5}, {0, 0, -1, 0.5}, {0, 0, 1, 0.5}};
        List<List<double[]>> rest = IplClipCells.subtract(List.of(List.of()), prism, cube);
        check(rest.size() == 5, "front + four lateral cells, got " + rest.size());
        check(essential(rest.get(0), cube).size() == 1, "front cell is one plane");
        partition(cube, ORIGIN, prism, rest, 1);

        // A wide prism covers the whole cross-section: only the front cell remains.
        double[][] wide = {{1, 0, 0, 0}, {0, -1, 0, 5}, {0, 1, 0, 5}, {0, 0, -1, 5}, {0, 0, 1, 5}};
        List<List<double[]>> front = IplClipCells.subtract(List.of(List.of()), wide, cube);
        check(front.size() == 1 && essential(front.get(0), cube).size() == 1, "body inside the aperture: one cut");

        // A prism that misses the box keeps the cell untouched (no planes at all).
        double[][] far = {{1, 0, 0, -3}, {0, -1, 0, 0.5}, {0, 1, 0, 0.5}, {0, 0, -1, 0.5}, {0, 0, 1, 0.5}};
        List<List<double[]>> untouched = IplClipCells.subtract(List.of(List.of()), far, cube);
        check(untouched.size() == 1 && untouched.get(0).isEmpty(), "missing prism changes nothing");

        // Redundant planes are dropped, essential ones kept in order.
        List<double[]> cell = new ArrayList<>(List.of(
            new double[]{1, 0, 0, 0}, new double[]{1, 0, 0, 5}, new double[]{0, 1, 0, 0.25}));
        List<double[]> kept = essential(cell, cube);
        check(kept.size() == 2 && kept.get(0)[3] == 0 && kept.get(1)[1] == 1, "redundant plane removed");

        // Rotated, translated box (45 degrees about z) against two prisms in sequence.
        double s = Math.sqrt(0.5);
        double[][] tilted = IplClipCells.box(new double[]{3, -2, 1},
            new double[][]{{2 * s, 2 * s, 0}, {-s, s, 0}, {0, 0, 0.75}}, 0.0);
        double[][] a = {{1, 0, 0, -3}, {0, -1, 0, -1.5}, {0, 1, 0, 2.5}, {0, 0, -1, 1.5}, {0, 0, 1, -0.5}};
        double[][] b = {{0, 1, 0, 1.75}, {-1, 0, 0, 3.5}, {1, 0, 0, -2.5}, {0, 0, -1, 9}, {0, 0, 1, 9}};
        List<List<double[]>> cells = IplClipCells.subtract(List.of(List.of()), a, tilted);
        cells = IplClipCells.subtract(cells, b, tilted);
        partition(tilted, new double[]{3, -2, 1}, null, cells, 2, a, b);

        // Random convex sets: the cells always tile box minus their union.
        Random random = new Random(7);
        for (int round = 0; round < 40; round++) {
            double[][] x = randomConvex(random);
            double[][] y = randomConvex(random);
            List<List<double[]>> out = IplClipCells.subtract(List.of(List.of()), x, cube);
            out = IplClipCells.subtract(out, y, cube);
            List<List<double[]>> reduced = new ArrayList<>();
            for (List<double[]> c : out) reduced.add(essential(c, cube));
            partition(cube, ORIGIN, null, reduced, 3 + round, x, y);
        }
        System.out.println("PASS: " + assertions + " clip-cell assertions");
    }

    private static List<double[]> essential(List<double[]> cell, double[][] box) {
        return IplClipCells.essential(cell, box);
    }

    /** Every sampled box point is in exactly one cell iff it is outside every subtracted set. */
    private static void partition(double[][] box, double[] center, double[][] single, List<List<double[]>> cells,
                                  int seed, double[][]... sets) {
        double[][][] subtracted = single != null ? new double[][][]{single} : sets;
        Random random = new Random(seed);
        int checked = 0;
        for (int i = 0; i < 20000; i++) {
            double[] p = {center[0] + random.nextDouble() * 5 - 2.5, center[1] + random.nextDouble() * 5 - 2.5,
                center[2] + random.nextDouble() * 5 - 2.5};
            if (!in(box, p, 0.0)) continue;
            if (nearBoundary(p, box, cells, subtracted)) continue;
            boolean outside = true;
            for (double[][] set : subtracted) if (in(set, p, 0.0)) outside = false;
            int owners = 0;
            for (List<double[]> cell : cells) if (in(cell.toArray(new double[0][]), p, 0.0)) owners++;
            check(owners == (outside ? 1 : 0), "point owned by " + owners + " cells, outside=" + outside);
            checked++;
        }
        check(checked > 200, "enough samples inside the box: " + checked);
    }

    private static boolean nearBoundary(double[] p, double[][] box, List<List<double[]>> cells, double[][][] sets) {
        for (double[] plane : box) if (Math.abs(eval(plane, p)) < 1e-3) return true;
        for (double[][] set : sets) for (double[] plane : set) if (Math.abs(eval(plane, p)) < 1e-3) return true;
        for (List<double[]> cell : cells) for (double[] plane : cell) if (Math.abs(eval(plane, p)) < 1e-3) return true;
        return false;
    }

    private static boolean in(double[][] planes, double[] p, double tolerance) {
        for (double[] plane : planes) if (eval(plane, p) < -tolerance) return false;
        return true;
    }

    private static double eval(double[] plane, double[] p) {
        return plane[0] * p[0] + plane[1] * p[1] + plane[2] * p[2] + plane[3];
    }

    private static double[][] randomConvex(Random random) {
        int n = 3 + random.nextInt(4);
        double[][] out = new double[n][];
        double[] c = {random.nextDouble() * 1.6 - 0.8, random.nextDouble() * 1.6 - 0.8, random.nextDouble() * 1.6 - 0.8};
        for (int i = 0; i < n; i++) {
            double[] v = {random.nextGaussian(), random.nextGaussian(), random.nextGaussian()};
            double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            v[0] /= len; v[1] /= len; v[2] /= len;
            double r = 0.2 + random.nextDouble() * 0.8;
            // Kept where v·(p - c) <= r, i.e. -v·p + v·c + r >= 0.
            out[i] = new double[]{-v[0], -v[1], -v[2], v[0] * c[0] + v[1] * c[1] + v[2] * c[2] + r};
        }
        return out;
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
