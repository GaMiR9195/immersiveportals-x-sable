package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

/**
 * Geometric checks for "did this sub-level cross this portal's plane this tick."
 *
 * <p>Used by {@link SableTransitController} once per tick per (sub-level, nearby-portal)
 * pair to decide whether to fire a transit event. Pure static utility; no state.
 *
 * <p>Phase 1 implementation: bounding-box overlap + portal-plane-crossing detection by
 * the airship's center pose. Phase 4 will add hysteresis to prevent ping-pong if an
 * airship parks within a portal's volume.
 */
public final class PortalCrossingDetector {

    private PortalCrossingDetector() {}

    /**
     * @return true iff the airship's logical-pose center crossed the portal's plane
     *         this tick (sign change in the plane's signed distance between lastPose
     *         and logicalPose) AND the crossing happened within the portal's volume
     *         (bounding-box overlap proxy).
     */
    public static boolean didCrossThisTick(ServerSubLevel airship, Portal portal) {
        if (airship.isRemoved()) return false;

        Vector3dc current = airship.logicalPose().position();
        Vector3dc previous = airship.lastPose().position();

        // No movement -> can't have crossed. Avoids spurious fires when the airship
        // is stationary atop the plane.
        if (current.equals(previous, 1e-9)) return false;

        Vec3 currentPos = new Vec3(current.x(), current.y(), current.z());
        Vec3 previousPos = new Vec3(previous.x(), previous.y(), previous.z());

        Plane portalPlane = new Plane(portal.getOriginPos(), portal.getNormal());
        double currentDistance = portalPlane.getDistanceTo(currentPos);
        double previousDistance = portalPlane.getDistanceTo(previousPos);

        // Sign change in signed distance to the plane indicates crossing.
        // Use strict-zero handling: if one side is exactly 0, treat as "crossed if
        // moving away from that side."
        boolean signFlipped = (currentDistance > 0) != (previousDistance > 0)
            && (currentDistance != 0 || previousDistance != 0);
        if (!signFlipped) return false;

        // Confine to portal volume: only fire if the airship's bounding box overlaps
        // the portal's. This is a cheap proxy for "the crossing happened within the
        // finite portal area" -- a stationary airship far above the portal won't
        // trigger a transit just because its plane-distance flipped sign due to drift.
        return airship.boundingBox().intersects(portal.getBoundingBox());
    }
}
