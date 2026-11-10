package ipl.sable.transit;

import java.util.List;

/** Pure geometry and frame voting for completed material/joint groups; no timers or prediction. */
public final class IplTransitSettlement {
    private static final double CLEARANCE = 1.0e-5;
    private IplTransitSettlement() {}

    /** Closed material envelope. Max coordinates are the outer voxel faces, not block indices. */
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static Bounds voxel(int x, int y, int z) { return new Bounds(x, y, z, x + 1.0, y + 1.0, z + 1.0); }
        public Bounds union(Bounds b) {
            return b == null ? this : new Bounds(Math.min(minX, b.minX), Math.min(minY, b.minY), Math.min(minZ, b.minZ),
                Math.max(maxX, b.maxX), Math.max(maxY, b.maxY), Math.max(maxZ, b.maxZ));
        }
        public double sideMin(IplPieceMap.Aperture a) { return sideCentre(a) - support(a.nx(), a.ny(), a.nz()); }
        public double sideMax(IplPieceMap.Aperture a) { return sideCentre(a) + support(a.nx(), a.ny(), a.nz()); }
        private double sideCentre(IplPieceMap.Aperture a) {
            return a.side((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
        }
        private double support(double x, double y, double z) {
            return ((maxX - minX) * Math.abs(x) + (maxY - minY) * Math.abs(y) + (maxZ - minZ) * Math.abs(z)) * 0.5;
        }
    }

    public static Bounds union(Bounds a, Bounds b) { return a == null ? b : a.union(b); }
    public static boolean inFront(Bounds b, IplPieceMap.Aperture a) { return b != null && b.sideMin(a) > CLEARANCE; }
    public static boolean behind(Bounds b, IplPieceMap.Aperture a) { return b != null && b.sideMax(a) < -CLEARANCE; }

    /** Exact SAT for an AABB and a finite oriented rectangle (a zero-thickness OBB). */
    public static boolean intersects(Bounds b, IplPieceMap.Aperture a) {
        if (b == null) return false;
        double[][] axes = {{1,0,0}, {0,1,0}, {0,0,1},
            {a.wx(),a.wy(),a.wz()}, {a.hx(),a.hy(),a.hz()}, {a.nx(),a.ny(),a.nz()}};
        for (double[] axis : axes) if (separated(b, a, axis[0], axis[1], axis[2])) return false;
        for (int i = 0; i < 3; i++) for (int j = 3; j < 6; j++) {
            double[] u = axes[i], v = axes[j];
            if (separated(b, a, u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])) return false;
        }
        return true;
    }

    private static boolean separated(Bounds b, IplPieceMap.Aperture a, double x, double y, double z) {
        double len2 = x*x + y*y + z*z;
        if (len2 < 1.0e-20) return false;
        double dx = a.ox() - (b.minX + b.maxX)*0.5;
        double dy = a.oy() - (b.minY + b.maxY)*0.5;
        double dz = a.oz() - (b.minZ + b.maxZ)*0.5;
        double rectangle = a.halfW()*Math.abs(a.wx()*x + a.wy()*y + a.wz()*z)
            + a.halfH()*Math.abs(a.hx()*x + a.hy()*y + a.hz()*z);
        return Math.abs(dx*x + dy*y + dz*z) > b.support(x,y,z) + rectangle + CLEARANCE*Math.sqrt(len2);
    }

    /** A whole group behind an already-used path has stronger evidence than stale native slivers. */
    public record Vote(int frame, long material, boolean through) {}

    /** Frame 0 is the native frame. Equivalent paths must be aggregated by the caller first. */
    public static int chooseFrame(long nativeMaterial, List<Vote> votes) {
        boolean hasThrough = votes.stream().anyMatch(v -> v.material > 0 && v.through);
        int best = 0;
        long weight = hasThrough ? -1 : nativeMaterial;
        for (Vote vote : votes) {
            if (vote.material <= 0 || hasThrough && !vote.through) continue;
            if (vote.material > weight || vote.material == weight && vote.frame < best) {
                best = vote.frame;
                weight = vote.material;
            }
        }
        return best;
    }

    /** Subdivide only observed angular motion; arbitrary linear displacement needs no speed cutoff. */
    public static int angularSlices(double angle, double radius) {
        if (!Double.isFinite(angle) || !Double.isFinite(radius)) return 1;
        return Math.max(1, Math.min(128, (int)Math.ceil(Math.max(angle / Math.toRadians(5), angle*Math.max(1, radius)/0.25))));
    }
}
