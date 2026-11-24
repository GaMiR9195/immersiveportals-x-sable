package ipl.sable.transit;

/**
 * Collision boxes trimmed at an aperture plane, shared by entity collision against the pieces of a
 * straddling body: its blocks ({@link IplStraddleCollisionClip}) and its contraptions
 * ({@link IplContraptionCollision}).
 *
 * <p>A box stays a box: an axis-aligned plane cuts it exactly; an oblique one cuts it per slice
 * along the plane's dominant axis, each slice keeping the tightest box around its kept part, so at
 * most a thin staircase is left behind the plane. Nothing ever grows past the input box.
 */
public final class IplBoxCut {

    /** Receives the kept boxes. */
    @FunctionalInterface
    public interface Sink {
        void accept(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    /** Thinner boxes are dropped: SAT on a zero-thickness box pins entities against material that is gone. */
    private static final double SLIVER = 1.0e-6;
    /** A normal this close to an axis cuts in one piece (exact for an axis-aligned plane). */
    private static final double AXIS_ALIGNED = 0.9999;
    private static final int OBLIQUE_SLICES = 4;

    private IplBoxCut() {}

    /** Passes the part of the box on the kept side of {@code plane} to {@code out}; a whole box passes unchanged. */
    public static void cut(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           IplStraddlePoseMap.LocalHalfSpace plane, Sink out) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        if (plane.signedDistance(nx > 0 ? minX : maxX, ny > 0 ? minY : maxY, nz > 0 ? minZ : maxZ) >= 0.0) {
            out.accept(minX, minY, minZ, maxX, maxY, maxZ);
            return;
        }
        if (plane.signedDistance(nx > 0 ? maxX : minX, ny > 0 ? maxY : minY, nz > 0 ? maxZ : minZ) <= 0.0) return;
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        int axis = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
        int slices = Math.max(ax, Math.max(ay, az)) >= AXIS_ALIGNED ? 1 : OBLIQUE_SLICES;
        double lo = axis == 0 ? minX : axis == 1 ? minY : minZ;
        double hi = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
        double step = (hi - lo) / slices;
        for (int k = 0; k < slices; k++) {
            double a = lo + step * k, b = k == slices - 1 ? hi : lo + step * (k + 1);
            switch (axis) {
                case 0 -> keep(a, minY, minZ, b, maxY, maxZ, plane, out);
                case 1 -> keep(minX, a, minZ, maxX, b, maxZ, plane, out);
                default -> keep(minX, minY, a, maxX, maxY, b, plane, out);
            }
        }
    }

    /** The tightest box around the kept part of one box (the box of its intersection with the half-space). */
    private static void keep(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                             IplStraddlePoseMap.LocalHalfSpace plane, Sink out) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double fx = nx > 0 ? maxX : minX, fy = ny > 0 ? maxY : minY, fz = nz > 0 ? maxZ : minZ;
        double front = plane.signedDistance(fx, fy, fz);
        if (front <= 0.0) return;
        // Moving away from the front corner along one axis keeps the point until its distance is spent.
        if (nx > 0) minX = Math.max(minX, fx - front / nx); else if (nx < 0) maxX = Math.min(maxX, fx - front / nx);
        if (ny > 0) minY = Math.max(minY, fy - front / ny); else if (ny < 0) maxY = Math.min(maxY, fy - front / ny);
        if (nz > 0) minZ = Math.max(minZ, fz - front / nz); else if (nz < 0) maxZ = Math.min(maxZ, fz - front / nz);
        if (maxX - minX <= SLIVER || maxY - minY <= SLIVER || maxZ - minZ <= SLIVER) return;
        out.accept(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
