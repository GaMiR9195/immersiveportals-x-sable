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
    public record CrossingState(CrossingPhase phase, double leadingEdge, double trailingEdge) {}

    /**
     * Evaluate the airship's crossing phase against {@code portal}, projecting its
     * OBB onto {@code sourceToDestNormal} (already oriented + locked by the caller).
     * The plane passes through {@code portal.getOriginPos()}.
     */
    public static CrossingState evaluate(ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal) {
        Vec3 planePos = portal.getOriginPos();
        Pose3dc pose = airship.logicalPose();

        LevelPlot plot = airship.getPlot();
        BoundingBox3ic local = plot.getBoundingBox();

        // Project the 8 OBB corners (local bounds transformed by the sub-level pose)
        // onto the source->dest normal; track min (trailing edge) and max (leading
        // edge) signed distance to the plane.
        double dMin = Double.POSITIVE_INFINITY;
        double dMax = Double.NEGATIVE_INFINITY;

        double[] xs = {local.minX(), local.maxX()};
        double[] ys = {local.minY(), local.maxY()};
        double[] zs = {local.minZ(), local.maxZ()};
        for (double lx : xs) {
            for (double ly : ys) {
                for (double lz : zs) {
                    Vec3 world = pose.transformPosition(new Vec3(lx, ly, lz));
                    double d = (world.x - planePos.x) * sourceToDestNormal.x
                             + (world.y - planePos.y) * sourceToDestNormal.y
                             + (world.z - planePos.z) * sourceToDestNormal.z;
                    if (d < dMin) dMin = d;
                    if (d > dMax) dMax = d;
                }
            }
        }

        CrossingPhase phase;
        if (dMin >= 0.0) {
            phase = CrossingPhase.CROSSED;       // trailing edge cleared the plane
        } else if (dMax >= 0.0) {
            phase = CrossingPhase.STRADDLING;    // leading edge through, trailing not
        } else {
            phase = CrossingPhase.APPROACHING;   // leading edge not yet at the plane
        }
        return new CrossingState(phase, dMax, dMin);
    }
}
