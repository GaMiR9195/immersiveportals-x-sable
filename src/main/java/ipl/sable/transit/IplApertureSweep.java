package ipl.sable.transit;

/** Geometry of an observed aperture sweep. No velocity extrapolation or speed cutoff. */
public final class IplApertureSweep {
    private IplApertureSweep() {}

    /** Crossing fraction, or NaN if the centre did not pass the finite rectangle. */
    public static double crossing(IplPieceMap.Aperture before, IplPieceMap.Aperture now,
                                  double x, double y, double z, double margin) {
        double s0 = before.side(x, y, z), s1 = now.side(x, y, z);
        if ((s0 >= 0.0) == (s1 >= 0.0)) return Double.NaN;
        double t = s0 / (s0 - s1);
        double u = lerp(before.u(x, y, z), now.u(x, y, z), t);
        double v = lerp(before.v(x, y, z), now.v(x, y, z), t);
        return Math.abs(u) <= lerp(before.halfW(), now.halfW(), t) + margin
            && Math.abs(v) <= lerp(before.halfH(), now.halfH(), t) + margin ? t : Double.NaN;
    }

    /** Conservative section broad phase for the same observed sweep used by ownership. */
    public static boolean boxMayIntersect(IplPieceMap.Aperture before, IplPieceMap.Aperture now,
                                          double x, double y, double z, double size, double margin) {
        double sMin = Double.POSITIVE_INFINITY, sMax = Double.NEGATIVE_INFINITY;
        double uMin = sMin, uMax = sMax, vMin = sMin, vMax = sMax;
        for (IplPieceMap.Aperture aperture : new IplPieceMap.Aperture[]{before, now}) {
            for (int i = 0; i < 8; i++) {
                double px = x + ((i & 1) == 0 ? 0 : size);
                double py = y + ((i & 2) == 0 ? 0 : size);
                double pz = z + ((i & 4) == 0 ? 0 : size);
                double side = aperture.side(px, py, pz);
                double u = aperture.u(px, py, pz), v = aperture.v(px, py, pz);
                double du = Math.abs(u) <= aperture.halfW() ? 0.0 : u - Math.copySign(aperture.halfW(), u);
                double dv = Math.abs(v) <= aperture.halfH() ? 0.0 : v - Math.copySign(aperture.halfH(), v);
                sMin = Math.min(sMin, side); sMax = Math.max(sMax, side);
                uMin = Math.min(uMin, du); uMax = Math.max(uMax, du);
                vMin = Math.min(vMin, dv); vMax = Math.max(vMax, dv);
            }
        }
        return sMin <= margin && sMax >= -margin && uMin <= margin && uMax >= -margin
            && vMin <= margin && vMax >= -margin;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
