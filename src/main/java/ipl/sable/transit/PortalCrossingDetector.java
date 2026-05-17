package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.AABB;
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
 * <p><b>Phase 1 update — centroid-based crossing:</b> originally we sign-flipped on
 * {@code logicalPose().position()}, which is the rigid body's origin. On an
 * asymmetric airship (blocks placed off-center from the plot origin), that point
 * sits at an arbitrary offset from the visible center of mass, so the transit
 * could fire when only the leading edge of the airship had crossed the portal
 * plane — visually "too early."
 *
 * <p>We now sign-flip on the airship's AABB center (its visible geometric center).
 * That's what the player perceives as the airship's centroid. The previous-tick
 * center is computed by translating the current AABB center by
 * {@code (lastPose - logicalPose)}, which is exact for AABB centers because
 * rotation moves the AABB extents but the AABB center tracks the pose origin's
 * delta (rotation doesn't shift the AABB's geometric center under the same pose
 * origin).
 *
 * <p>Phase 4 will add hysteresis to prevent ping-pong if an airship parks within
 * a portal's volume.
 */
public final class PortalCrossingDetector {

    private PortalCrossingDetector() {}

    /**
     * @return true iff the airship's <b>AABB center</b> crossed the portal's plane
     *         this tick (sign change in the plane's signed distance between the
     *         previous-tick and current-tick AABB centers) AND the airship's
     *         bounding box overlaps the portal's volume.
     */
    public static boolean didCrossThisTick(ServerSubLevel airship, Portal portal) {
        if (airship.isRemoved()) return false;

        Vector3dc currentOrigin = airship.logicalPose().position();
        Vector3dc previousOrigin = airship.lastPose().position();

        // No movement -> can't have crossed. Avoids spurious fires when the airship
        // is stationary atop the plane.
        if (currentOrigin.equals(previousOrigin, 1e-9)) return false;

        // Current AABB center (world space, geometric center of the airship's
        // visible bounds at logicalPose).
        AABB currentBox = airship.boundingBox().toMojang();
        Vec3 currentCenter = currentBox.getCenter();

        // Previous AABB center: translate currentCenter by the pose-origin delta.
        // Rotation between ticks shifts the AABB extents but not its center
        // relative to the pose origin -- so the previous AABB center, in world
        // space, equals (currentCenter - originDelta).
        double dx = currentOrigin.x() - previousOrigin.x();
        double dy = currentOrigin.y() - previousOrigin.y();
        double dz = currentOrigin.z() - previousOrigin.z();
        Vec3 previousCenter = new Vec3(
            currentCenter.x - dx,
            currentCenter.y - dy,
            currentCenter.z - dz
        );

        Plane portalPlane = new Plane(portal.getOriginPos(), portal.getNormal());
        double currentDistance = portalPlane.getDistanceTo(currentCenter);
        double previousDistance = portalPlane.getDistanceTo(previousCenter);

        // Sign change in signed distance to the plane indicates crossing.
        // Strict-zero handling: if exactly one side is 0, treat as "crossed if
        // the other side is non-zero."
        boolean signFlipped = (currentDistance > 0) != (previousDistance > 0)
            && (currentDistance != 0 || previousDistance != 0);
        if (!signFlipped) return false;

        // Confine to portal volume: even if the centroid sign-flipped, only fire
        // if the airship's bounding box overlaps the portal's. A stationary
        // airship far from the portal whose centroid happens to be on the plane's
        // axis (e.g., teleported there) wouldn't trigger a phantom transit.
        return airship.boundingBox().intersects(portal.getBoundingBox());
    }
}
