package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** PortalPrism: the through region is the aperture swept along the passage, never the plane. */
public final class PortalPrismTest {
    private static int assertions;

    public static void main(String[] args) {
        // Fixture portal: origin 0, normal (0,0,-1) so the inward direction is +z, 10 x 10.
        Portal portal = new Portal();
        double half = 5 + PortalPrism.LATERAL_MARGIN;

        PortalPrism straight = PortalPrism.straight(portal, 100);
        check(straight.contains(0, 0, 1), "through the aperture");
        check(!straight.contains(0, 0, -0.5), "in front of the plane");
        check(straight.contains(0, 0, -PortalPrism.SKIN * 0.5), "skin in front of the plane");
        check(!straight.contains(half + 0.5, 0, 1), "beyond the plane BESIDE the aperture is not through");
        check(straight.contains(half - 0.01, 0, 1), "lateral margin");
        check(!straight.contains(0, 0, 101), "depth bound");
        double[] t = straight.interval(new Vec3(0, 0, -5), new Vec3(0, 0, 1));
        check(t != null && near(t[0], 5 - PortalPrism.SKIN) && near(t[1], 105), "ray interval along the normal");
        check(straight.interval(new Vec3(8, 0, -5), new Vec3(0, 0, 1)) == null, "ray beside the aperture never enters");

        // Slanted: the anchor went in 2 deep and 3 sideways (slope 1.5 = MAX_SLOPE).
        PortalPrism slanted = PortalPrism.of(portal, new Vec3(3, 0, 2), 100);
        check(near(slanted.passage().x, 1.5) && near(slanted.passage().z, 1), "passage along the anchor chord");
        check(slanted.contains(3, 0, 2), "anchor inside");
        check(slanted.contains(8, 0, 2) && !straight.contains(8, 0, 2), "slanted passage keeps oblique material");
        check(!slanted.contains(-4, 0, 2) && straight.contains(-4, 0, 2), "slanted passage drops the other side");
        PortalPrism steep = PortalPrism.of(portal, new Vec3(30, 0, 2), 100);
        check(near(steep.passage().x, PortalPrism.MAX_SLOPE), "slope clamped");
        check(PortalPrism.of(portal, new Vec3(3, 0, -1), 100).passage().x == 0, "anchor in front: straight");
        PortalPrism shallow = PortalPrism.of(portal, new Vec3(0.1, 0, 0.25), 100);
        check(near(shallow.passage().x, 0.1 * 0.25 / (PortalPrism.FULL_SLOPE_DEPTH * PortalPrism.FULL_SLOPE_DEPTH)),
            "shallow chord fades toward the normal");

        // Render faces agree with contains() away from the boundary.
        java.util.Random random = new java.util.Random(7);
        for (PortalPrism prism : new PortalPrism[]{straight, slanted}) {
            double[][] faces = prism.faces();
            check(faces.length == 5, "portal face plus four laterals");
            for (int i = 0; i < 2000; i++) {
                double x = random.nextDouble() * 24 - 12, y = random.nextDouble() * 24 - 12;
                double z = random.nextDouble() * 6 - 1;
                boolean all = true;
                double margin = Double.MAX_VALUE;
                for (double[] f : faces) {
                    double v = f[0] * x + f[1] * y + f[2] * z + f[3];
                    all &= v >= 0;
                    margin = Math.min(margin, Math.abs(v));
                }
                if (margin < 1e-3) continue;
                check(all == prism.contains(x, y, z), "faces == contains at " + x + "," + y + "," + z);
            }
        }

        double[] region = slanted.region(2);
        check(region.length == 17 && region[14] == PortalPrism.SKIN && region[15] == 100 && region[16] == 2,
            "native region layout");
        check(near(region[9], half) && near(region[13], half), "half extents with margin");
        System.out.println("PASS: " + assertions + " portal-prism assertions");
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
