package ipl.sable.transit;

/** Dependency-free continuous SAT for a moving block OBB and a finite portal plate.
 * Exact for translation with fixed basis. Curved motion is subdivided by the caller,
 * which supplies a bound on the deviation of every vertex from its endpoint chord.
 * All arrays are xyz vectors; basis contains the three HALF-edge vectors.
 */
final class PortalSweepMath {
    private static final double EPS = 1e-9;
    private PortalSweepMath() {}

    record Hit(double enter, double leave) {}

    static Hit sweep(double[] a, double[] b, double[][] basisA, double[][] basisB,
                     double[] origin, double[] w, double[] h, double[] n,
                     double halfW, double halfH, double thickness, double curvature) {
        double[] interval = {0.0, 1.0};
        if (!clip(interval, a, b, basisA, basisB, origin, w, h, w,
            halfW, halfH, n, thickness, curvature)
            || !clip(interval, a, b, basisA, basisB, origin, w, h, h,
            halfW, halfH, n, thickness, curvature)
            || !clip(interval, a, b, basisA, basisB, origin, w, h, n,
            halfW, halfH, n, thickness, curvature)) return null;
        boolean sameBasis = java.util.Arrays.deepEquals(basisA, basisB);
        for (int i = 0; i < (sameBasis ? 1 : 2); i++) {
            double[][] basis = i == 0 ? basisA : basisB;
            for (double[] edge : basis) {
                if (!clip(interval, a, b, basisA, basisB, origin, w, h, edge,
                    halfW, halfH, n, thickness, curvature)) return null;
                for (double[] portalAxis : new double[][]{w, h, n}) {
                    double[] axis = cross(edge, portalAxis);
                    if (!clip(interval, a, b, basisA, basisB, origin, w, h, axis,
                        halfW, halfH, n, thickness, curvature)) return null;
                }
            }
        }
        return new Hit(interval[0], interval[1]);
    }

    private static boolean clip(double[] interval, double[] a, double[] b,
                                double[][] basisA, double[][] basisB,
                                double[] origin, double[] w, double[] h, double[] axis,
                                double halfW, double halfH, double[] n,
                                double thickness, double curvature) {
        double length = Math.sqrt(dot(axis, axis));
        if (length < EPS) return true;
        double d0 = (a[0] - origin[0]) * axis[0] + (a[1] - origin[1]) * axis[1]
            + (a[2] - origin[2]) * axis[2];
        double delta = (b[0] - a[0]) * axis[0] + (b[1] - a[1]) * axis[1]
            + (b[2] - a[2]) * axis[2];
        double portal = halfW * Math.abs(dot(w, axis)) + halfH * Math.abs(dot(h, axis))
            + thickness * Math.abs(dot(n, axis));
        double r0 = support(basisA, axis), r1 = support(basisB, axis);
        double radius = portal + r0 + (curvature + EPS) * length;
        double dr = r1 - r0;
        return upperBound(interval, d0 - radius, delta - dr)
            && upperBound(interval, -d0 - radius, -delta - dr);
    }

    /** Clip c + v*t <= 0, returning the FINAL shared aperture/plane interval. */
    private static boolean upperBound(double[] range, double c, double v) {
        if (Math.abs(v) < 1e-15) return c <= 0.0;
        double t = -c / v;
        if (v > 0.0) range[1] = Math.min(range[1], t);
        else range[0] = Math.max(range[0], t);
        return range[0] <= range[1];
    }

    static double support(double[][] basis, double[] axis) {
        return Math.abs(dot(basis[0], axis)) + Math.abs(dot(basis[1], axis))
            + Math.abs(dot(basis[2], axis));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]};
    }
}
