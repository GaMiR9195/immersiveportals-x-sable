package ipl.sable.transit;

import java.util.ArrayList;
import java.util.List;

/** IplCollisionRegion: generous block test and box pieces of native and node frames. */
public final class CollisionRegionTest {
    private static int assertions;

    /** Through prism of a portal in the plane x = 0: beyond = x >= 0, aperture |y|, |z| <= 2. */
    private static double[][] prism(double depth0, double half) {
        return new double[][]{
            {1, 0, 0, -depth0},
            {0, 1, 0, half}, {0, -1, 0, half},
            {0, 0, 1, half}, {0, 0, -1, half}};
    }

    public static void main(String[] args) {
        double[][] portal = prism(0, 2);
        IplCollisionRegion nativeFrame = new IplCollisionRegion(List.of(), List.<double[][]>of(portal));
        IplCollisionRegion node = new IplCollisionRegion(List.of(portal), List.<double[][]>of(prism(5, 1)));

        // Whole-block filter.
        check(nativeFrame.keepsCube(-0.5, 0, 0, 0.5), "native keeps the front side");
        check(!nativeFrame.keepsCube(0.5, 0, 0, 0.5), "native drops what went into the aperture");
        check(nativeFrame.keepsCube(0.5, 5, 0, 0.5), "native keeps material beyond the plane BESIDE the aperture");
        check(nativeFrame.keepsCube(0.5, 2, 0, 0.5), "native keeps a block across the aperture side");
        check(node.keepsCube(0.5, 0, 0, 0.5), "node keeps what went through");
        check(!node.keepsCube(-1, 0, 0, 0.5), "node drops the front side");
        check(node.keepsCube(-0.5, 0, 0, 0.5), "a block touching the plane stays a candidate");
        check(!node.keepsCube(0.5, 5, 0, 0.5), "node drops material beside the aperture");
        check(!node.keepsCube(5.5, 0, 0, 0.5), "node drops what went into its child");
        check(node.keepsCube(5.5, 1.5, 0, 0.5), "node keeps what passed beside its child");

        // Box pieces.
        List<double[]> out = new ArrayList<>();
        check(!nativeFrame.clip(box(-1, -0.5, -0.5, 0, 0.5, 0.5), out) && out.isEmpty(),
            "front box untouched (touching the plane)");
        check(!nativeFrame.clip(box(0, 4, -0.5, 1, 5, 0.5), out) && out.isEmpty(),
            "box beside the aperture untouched");
        check(nativeFrame.clip(box(0, -0.5, -0.5, 1, 0.5, 0.5), out) && out.isEmpty(),
            "box inside the prism removed");
        out.clear();
        check(nativeFrame.clip(box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), out) && out.size() == 1,
            "box across the plane: one piece");
        check(same(out.get(0), box(-0.5, -0.5, -0.5, 0, 0.5, 0.5)), "front half kept exactly");
        out.clear();
        nativeFrame.clip(box(-0.5, 1.5, -0.5, 0.5, 2.5, 0.5), out);
        check(out.size() == 2, "box across plane and side: two pieces");
        check(covers(out, -0.25, 1.75, 0) && covers(out, 0.25, 2.25, 0) && covers(out, -0.25, 2.25, 0),
            "L-shaped remainder covered");
        check(!covers(out, 0.25, 1.75, 0), "nothing inside the prism");
        out.clear();
        check(node.clip(box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), out) && out.size() == 1
            && same(out.get(0), box(0, -0.5, -0.5, 0.5, 0.5, 0.5)), "node keeps the beyond half");
        out.clear();
        check(nativeFrame.clip(box(-5e-8, -0.5, -0.5, 1, 0.5, 0.5), out) && out.isEmpty(),
            "round-off behind the plane leaves no sliver");

        // Piece budget.
        List<double[][]> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(prism(0.05 * i, 0.1 + 0.05 * i));
        out.clear();
        new IplCollisionRegion(List.of(), many).clip(box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), out);
        check(!out.isEmpty() && out.size() <= IplCollisionRegion.MAX_PIECES + 4, "piece count bounded");

        // Distance to owned material (collision frame choice).
        double[] ship = box(-5, -5, -1, 5, 5, 1);
        check(near(nativeFrame.distanceSq(ship, 3, 0, 0), 4.0), "through point is 2 blocks from the native deck beside it");
        check(near(nativeFrame.distanceSq(ship, 3, 4, 0), 0.0), "native owns the deck beside the aperture");
        check(near(node.distanceSq(ship, 3, 4, 0), 4.0), "node is 2 blocks away from beside-deck");
        check(near(node.distanceSq(ship, 3, 0, 0), 0.0), "node owns the through part");

        // Change of frame.
        IplCollisionRegion moved = nativeFrame.map(p -> new double[]{p[0], p[1], p[2], p[3] - p[0] * 10});
        check(!moved.keepsCube(10.5, 0, 0, 0.5) && moved.keepsCube(9.5, 0, 0, 0.5), "mapped region follows the frame");
        check(new IplCollisionRegion(List.of(), List.of()).isUnbounded(), "no planes: unbounded");

        System.out.println("PASS: " + assertions + " collision-region assertions");
    }

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    private static boolean same(double[] a, double[] b) {
        for (int i = 0; i < 6; i++) if (Math.abs(a[i] - b[i]) > 1e-9) return false;
        return true;
    }

    private static boolean covers(List<double[]> boxes, double x, double y, double z) {
        for (double[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
        }
        return false;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
