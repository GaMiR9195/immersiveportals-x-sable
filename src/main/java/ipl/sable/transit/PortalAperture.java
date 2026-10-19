package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** Shared finite-plate SAT for a rendered plot OBB (not its world-axis AABB).
 * The server sweep uses the same SAT, on individual occupied blocks instead.
 */
public final class PortalAperture {
    private PortalAperture() {}

    public static boolean intersects(BoundingBox3ic bounds, Pose3dc pose, Portal portal,
                                     double margin, double thickness) {
        if (bounds == null || portal.isRemoved()) return false;
        double x = (bounds.minX() + (double) bounds.maxX() + 1.0) * .5;
        double y = (bounds.minY() + (double) bounds.maxY() + 1.0) * .5;
        double z = (bounds.minZ() + (double) bounds.maxZ() + 1.0) * .5;
        Vec3 center = pose.transformPosition(new Vec3(x, y, z));
        double[][] basis = {
            v(pose.transformPosition(new Vec3(bounds.maxX() + 1.0, y, z)).subtract(center)),
            v(pose.transformPosition(new Vec3(x, bounds.maxY() + 1.0, z)).subtract(center)),
            v(pose.transformPosition(new Vec3(x, y, bounds.maxZ() + 1.0)).subtract(center))
        };
        double[] c = v(center);
        return PortalSweepMath.sweep(c, c, basis, basis, v(portal.getOriginPos()),
            v(portal.getAxisW()), v(portal.getAxisH()), v(portal.getNormal()),
            portal.getWidth() * .5 + margin, portal.getHeight() * .5 + margin,
            thickness, 0.0) != null;
    }

    private static double[] v(Vec3 v) { return new double[]{v.x, v.y, v.z}; }
}
