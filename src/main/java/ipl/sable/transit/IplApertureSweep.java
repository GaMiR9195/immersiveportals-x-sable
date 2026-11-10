package ipl.sable.transit;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

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

    /** Observed portal animation between samples; no extrapolation past either endpoint. */
    public static IplPieceMap.Aperture interpolate(IplPieceMap.Aperture before, IplPieceMap.Aperture now, double t) {
        if (t <= 0.0) return before;
        if (t >= 1.0 || before.equals(now)) return now;
        Quaterniond q = orientation(before).slerp(orientation(now), t);
        Vector3d w = q.transform(new Vector3d(1, 0, 0));
        Vector3d h = q.transform(new Vector3d(0, 1, 0));
        Vector3d n = q.transform(new Vector3d(0, 0, 1));
        return new IplPieceMap.Aperture(lerp(before.ox(),now.ox(),t), lerp(before.oy(),now.oy(),t),
            lerp(before.oz(),now.oz(),t), n.x,n.y,n.z, w.x,w.y,w.z, lerp(before.halfW(),now.halfW(),t),
            h.x,h.y,h.z, lerp(before.halfH(),now.halfH(),t));
    }

    public static double rotationAngle(IplPieceMap.Aperture before, IplPieceMap.Aperture now) {
        return 2.0*Math.acos(Math.min(1.0, Math.abs(orientation(before).dot(orientation(now)))));
    }

    private static Quaterniond orientation(IplPieceMap.Aperture a) {
        return new Quaterniond().setFromNormalized(new Matrix3d(a.wx(),a.wy(),a.wz(), a.hx(),a.hy(),a.hz(),
            a.nx(),a.ny(),a.nz())).normalize();
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
