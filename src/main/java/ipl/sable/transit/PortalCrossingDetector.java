package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Edge-interval crossing model for an airship straddling a portal.
 *
 * <p><b>The idea (and why it's rotation-invariant):</b> rather than track "the
 * leading edge" as a specific block -- which changes as the ship rotates -- we
 * project the ship's whole oriented bounding box onto the portal's (stable,
 * source->dest) normal and take the extremes. For a normal {@code n} pointing
 * source->dest and a plane through {@code p}, each corner {@code x} has signed
 * distance {@code d(x) = n . (x - p)}; dest-side points are positive, source-side
 * negative. Over the 8 OBB corners that yields an interval {@code [dMin, dMax]}:
 * <ul>
 *   <li>{@code dMax} = furthest toward dest = the <b>leading edge</b>;</li>
 *   <li>{@code dMin} = furthest toward source = the <b>trailing edge</b>.</li>
 * </ul>
 * {@code dMax} is a {@code max} over all corners, so as the ship rotates it
 * smoothly tracks whichever corner is currently furthest through -- no edge
 * identity to maintain, no velocity, no ship-fixed normal.
 *
 * <p>Three phases follow from the interval:
 * <table>
 *   <tr><th>Phase</th><th>Condition</th><th>Meaning</th></tr>
 *   <tr><td>APPROACHING</td><td>{@code dMax < 0}</td>
 *       <td>even the leading edge hasn't reached the plane -> mirror hidden</td></tr>
 *   <tr><td>STRADDLING</td><td>{@code dMin < 0 <= dMax}</td>
 *       <td>leading edge through, trailing not -> mirror visible</td></tr>
 *   <tr><td>CROSSED</td><td>{@code dMin >= 0}</td>
 *       <td>trailing edge passed -> whole ship dest-side -> swap</td></tr>
 * </table>
 *
 * <p>This is exactly the user's "deformation collapses when it equals the ship's
 * size" intuition: deformation depth (how far the leading edge has pushed past)
 * {@code = dMax}, ship size along the normal {@code = dMax - dMin}, and
 * {@code dMax >= dMax - dMin} simplifies to {@code dMin >= 0} -- the CROSSED
 * condition. The "deformed window" is the interval {@code [dMin, dMax]} sliding
 * through zero; it "collapses" the instant the trailing edge clears.
 *
 * <p><b>Stable normal:</b> the caller passes the source->dest normal locked at
 * first contact (see {@code SableTransitController}'s per-session normal lock),
 * NOT the portal's raw normal re-derived each tick. That lock keeps "toward dest"
 * consistent through a mid-straddle rotation -- without it, a rotating ship near a
 * two-faced portal could flip which side is dest and bounce between phases.
 *
 * <p><b>OBB, not world AABB:</b> we transform the plot's local bounds through the
 * sub-level pose to get a tight oriented box. World AABB would over-estimate --
 * showing the mirror early and swapping late. (The OBB is still the plot bounds,
 * not the exact block hull, so a sparse airship's empty corners count as "ship" --
 * swaps a hair conservatively. Acceptable; tightening to mass-tracker bounds is a
 * future refinement.)
 */
public final class PortalCrossingDetector {

    private PortalCrossingDetector() {}

    public enum CrossingPhase {
        /** Entire ship on the source side; leading edge hasn't reached the plane. */
        APPROACHING,
        /** Leading edge through the plane, trailing edge not yet -> show mirror. */
        STRADDLING,
        /** Entire ship past the plane (trailing edge cleared) -> swap. */
        CROSSED
    }

    /**
     * Result of an edge-interval evaluation: the phase plus the raw edge distances
     * (signed distance to the plane along the source->dest normal). {@code leadingEdge}
     * is the maximum (furthest toward dest), {@code trailingEdge} the minimum
     * (furthest toward source).
     */
    public record CrossingState(
        CrossingPhase phase, double leadingEdge, double trailingEdge,
        boolean intersectsPortalAperture,
        boolean enteredFromSourceAperture
    ) {}

    /**
     * Evaluate the airship's crossing phase against {@code portal}, projecting its
     * OBB onto {@code sourceToDestNormal} (already oriented + locked by the caller).
     * The plane passes through {@code portal.getOriginPos()}.
     */
    public static CrossingState evaluate(ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal) {
        return evaluate(airship, portal, sourceToDestNormal, 0.0);
    }

    /**
     * Evaluates a crossing with an optional lateral aperture expansion. The
     * expansion affects width/height only; the portal plane remains zero-thickness.
     */
    public static CrossingState evaluate(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal, double apertureMargin
    ) {
        LevelPlot plot = airship.getPlot();
        BoundingBox3ic local = plot.getBoundingBox();
        ObbSample current = sample(local, airship.logicalPose(), portal.getOriginPos(), sourceToDestNormal);
        ObbSample previous = sample(local, airship.lastPose(), portal.getOriginPos(), sourceToDestNormal);

        CrossingPhase phase = getPhase(current.minDistance, current.maxDistance);
        CrossingPhase previousPhase = getPhase(previous.minDistance, previous.maxDistance);
        boolean intersectsAperture = phase == CrossingPhase.STRADDLING
            && intersectsPortalAperture(portal, local, airship.logicalPose(), current.corners, current.distances,
            apertureMargin);
        boolean previousIntersectsAperture = previousPhase == CrossingPhase.STRADDLING
            && intersectsPortalAperture(portal, local, airship.lastPose(), previous.corners, previous.distances,
            apertureMargin);

        Vec3 portalNormal = portal.getNormal();
        double previousSourceDistance = previous.center.subtract(portal.getOriginPos()).dot(portalNormal);
        boolean enteredFromSourceAperture = previousSourceDistance > 0.0
            && previousPhase == CrossingPhase.APPROACHING
            && phase != CrossingPhase.APPROACHING
            && (intersectsAperture || previousIntersectsAperture
                || sweptThroughPortalAperture(portal, previous, current));
        return new CrossingState(
            phase, current.maxDistance, current.minDistance, intersectsAperture,
            enteredFromSourceAperture
        );
    }

    private static CrossingPhase getPhase(double dMin, double dMax) {
        if (dMin >= 0.0) {
            return CrossingPhase.CROSSED;
        }
        if (dMax >= 0.0) {
            return CrossingPhase.STRADDLING;
        }
        return CrossingPhase.APPROACHING;
    }

    private static ObbSample sample(
        BoundingBox3ic local, Pose3dc pose, Vec3 planePos, Vec3 sourceToDestNormal
    ) {

        // Project the 8 OBB corners (local bounds transformed by the sub-level pose)
        // onto the source->dest normal; track min (trailing edge) and max (leading
        // edge) signed distance to the plane.
        double dMin = Double.POSITIVE_INFINITY;
        double dMax = Double.NEGATIVE_INFINITY;
        Vec3[] corners = new Vec3[8];
        double[] distances = new double[8];
        int cornerIndex = 0;

        // Sable's plot bounds are inclusive block indices. ServerSubLevel's own
        // world bounding box uses max + 1.0 (see SubLevel.updateBoundingBox), so
        // the physical OBB must do the same. Using raw max here declared the
        // trailing block clear one block early and could flip parent dimensions
        // while that edge was still inside the output portal.
        double[] xs = {local.minX(), local.maxX() + 1.0};
        double[] ys = {local.minY(), local.maxY() + 1.0};
        double[] zs = {local.minZ(), local.maxZ() + 1.0};
        for (double lx : xs) {
            for (double ly : ys) {
                for (double lz : zs) {
                    Vec3 world = pose.transformPosition(new Vec3(lx, ly, lz));
                    double d = (world.x - planePos.x) * sourceToDestNormal.x
                             + (world.y - planePos.y) * sourceToDestNormal.y
                             + (world.z - planePos.z) * sourceToDestNormal.z;
                    corners[cornerIndex] = world;
                    distances[cornerIndex] = d;
                    cornerIndex++;
                    if (d < dMin) dMin = d;
                    if (d > dMax) dMax = d;
                }
            }
        }

        Vec3 center = pose.transformPosition(new Vec3(
            (local.minX() + local.maxX() + 1.0) * 0.5,
            (local.minY() + local.maxY() + 1.0) * 0.5,
            (local.minZ() + local.maxZ() + 1.0) * 0.5
        ));
        return new ObbSample(corners, distances, dMin, dMax, center);
    }

    /**
     * A sampled pose can move from fully source-side to fully destination-side in one
     * physics tick. Trace every OBB corner and its center through IP's finite portal
     * shape, so that valid high-speed crossings do not depend on an arbitrary distance
     * padding around the plane.
     */
    private static boolean sweptThroughPortalAperture(Portal portal, ObbSample previous, ObbSample current) {
        if (portal.rayTrace(previous.center, current.center) != null) {
            return true;
        }
        for (int i = 0; i < current.corners.length; i++) {
            if (portal.rayTrace(previous.corners[i], current.corners[i]) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The crossing plane alone is not a portal. Intersect the OBB's twelve edges
     * with that plane and require at least one hit to lie inside the finite portal
     * rectangle. This keeps an airship beside a portal from opening a straddle
     * session merely because its bounds happen to cross the plane's infinite span.
     */
    private static boolean intersectsPortalAperture(
        Portal portal, BoundingBox3ic local, Pose3dc pose, Vec3[] corners, double[] distances,
        double apertureMargin
    ) {
        // A portal can be completely inside a large OBB's plane cross-section,
        // leaving every OBB edge intersection outside the aperture. Its center
        // being inside the local box still proves the finite aperture overlaps it.
        Vec3 portalInLocal = pose.transformPositionInverse(portal.getOriginPos());
        if (portalInLocal.x >= local.minX() && portalInLocal.x <= local.maxX() + 1.0
            && portalInLocal.y >= local.minY() && portalInLocal.y <= local.maxY() + 1.0
            && portalInLocal.z >= local.minZ() && portalInLocal.z <= local.maxZ() + 1.0) {
            return true;
        }

        int[][] edges = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
        };
        for (int[] edge : edges) {
            int a = edge[0];
            int b = edge[1];
            double da = distances[a];
            double db = distances[b];
            if ((da < 0.0 && db < 0.0) || (da > 0.0 && db > 0.0)) continue;

            double denominator = da - db;
            double t = denominator == 0.0 ? 0.0 : da / denominator;
            Vec3 hit = corners[a].add(corners[b].subtract(corners[a]).scale(t));
            Vec3 fromOrigin = hit.subtract(portal.getOriginPos());
            double width = Math.abs(fromOrigin.dot(portal.getAxisW()));
            double height = Math.abs(fromOrigin.dot(portal.getAxisH()));
            if (width <= portal.getWidth() * 0.5 + apertureMargin
                && height <= portal.getHeight() * 0.5 + apertureMargin) {
                return true;
            }
        }
        return false;
    }

    private record ObbSample(
        Vec3[] corners, double[] distances, double minDistance, double maxDistance, Vec3 center
    ) {}
}
