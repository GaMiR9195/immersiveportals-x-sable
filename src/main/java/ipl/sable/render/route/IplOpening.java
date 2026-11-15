package ipl.sable.render.route;

import ipl.sable.transit.IplApertureGrace;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.PortalShape;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;

import java.util.UUID;

/**
 * A teleporting portal seen as an opening: its finite flat area in the world space of its own
 * level and the map into the level it leads to. {@link #normal} points to the entry (front) side.
 *
 * <p>Only flat teleporting portals are openings: a mirror or a view-only portal cannot be passed,
 * a box portal has no plane. Global portals (dimension stacking) are ordinary openings with a
 * very large rectangle. One instance is a snapshot of one render frame ({@link IplOpenings}).
 */
public final class IplOpening {

	/** Squared distance below which two portal anchors are one point. */
	private static final double SAME_POINT_SQ = 1.0e-4;
	/** |cos| above which two portal normals are one direction. */
	private static final double PARALLEL = 0.9999;
	/** Half thickness of the probe used to test a point against a non-rectangular shape. */
	private static final double SHAPE_PROBE = 1.0e-3;

	final Portal portal;
	final UUID id;
	final ResourceKey<Level> source;
	final ResourceKey<Level> destination;
	final Vector3d origin;
	final Vector3d normal;
	final Vector3d axisW;
	final Vector3d axisH;
	final double halfW;
	final double halfH;
	final Vec3 destinationOrigin;
	/** Source world → destination world (rotation, uniform scale, translation). */
	final Matrix4d transform;
	/** True when the rectangle is the exact opening; otherwise the shape refines it. */
	final boolean rectangular;

	private IplOpening(Portal portal) {
		this.portal = portal;
		this.id = portal.getUUID();
		this.source = portal.getOriginDim();
		this.destination = portal.getDestDim();
		this.origin = vector(portal.getOriginPos());
		this.axisW = vector(portal.getAxisW()).normalize();
		this.axisH = vector(portal.getAxisH()).normalize();
		this.normal = new Vector3d(axisW).cross(axisH).normalize();
		// The same grown opening as the server's pieces and sessions (IplApertureGrace).
		this.halfW = portal.getWidth() * 0.5 + IplApertureGrace.LATERAL;
		this.halfH = portal.getHeight() * 0.5 + IplApertureGrace.LATERAL;
		this.destinationOrigin = portal.getDestPos();
		this.transform = portal.getFullSpaceTransformation();
		this.rectangular = portal.getPortalShape() instanceof RectangularPortalShape;
	}

	/** The opening of {@code portal}, or null when it is no passable flat opening. */
	@Nullable
	public static IplOpening of(@Nullable Portal portal) {
		if (portal == null || portal.isRemoved() || !portal.isTeleportable()) return null;
		PortalShape shape = portal.getPortalShape();
		if (!shape.isPlanar() || portal.getDestDim() == null || portal.getOriginDim() == null) return null;
		if (!(portal.getWidth() > 0.0) || !(portal.getHeight() > 0.0)) return null;
		return new IplOpening(portal);
	}

	/** Same portal (portals on the client may be separate objects for one entity). */
	boolean sameAs(@Nullable IplOpening other) {
		return other != null && other.id.equals(id);
	}

	/**
	 * Whether this opening, met on the far side of {@code entry}, is its exit face: the reverse
	 * portal of the pair (or its flipped side) that leads straight back through {@code entry}.
	 * Crossing it returns to the frame before {@code entry} instead of opening a deeper one.
	 */
	boolean leadsBackThrough(IplOpening entry) {
		if (!source.equals(entry.destination) || !destination.equals(entry.source)) return false;
		if (origin.distanceSquared(entry.destinationOrigin.x, entry.destinationOrigin.y, entry.destinationOrigin.z) > SAME_POINT_SQ) {
			return false;
		}
		if (entry.origin.distanceSquared(destinationOrigin.x, destinationOrigin.y, destinationOrigin.z) > SAME_POINT_SQ) {
			return false;
		}
		Vector3d exitNormal = entry.transform.transformDirection(new Vector3d(entry.normal)).normalize();
		return Math.abs(exitNormal.dot(normal)) > PARALLEL;
	}

	/**
	 * Whether a point of this opening's plane (in its level's world space) lies in the opening.
	 * Only called for non-rectangular shapes after the rectangle test.
	 */
	boolean shapeContains(Vector3d point) {
		if (shapeHit(point.x, point.y, point.z)) return true;
		// The grace of the opening (IplApertureGrace) for any shape: a point within it of the shape
		// passes. Probed toward the centre, where every shape grows from.
		double grace = IplApertureGrace.LATERAL;
		if (grace <= 0.0) return false;
		double dx = origin.x - point.x, dy = origin.y - point.y, dz = origin.z - point.z;
		double along = dx * normal.x + dy * normal.y + dz * normal.z;
		dx -= normal.x * along;
		dy -= normal.y * along;
		dz -= normal.z * along;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= grace) return true;
		double k = grace / len;
		return shapeHit(point.x + dx * k, point.y + dy * k, point.z + dz * k);
	}

	private boolean shapeHit(double x, double y, double z) {
		Vec3 from = new Vec3(x + normal.x * SHAPE_PROBE, y + normal.y * SHAPE_PROBE, z + normal.z * SHAPE_PROBE);
		Vec3 to = new Vec3(x - normal.x * SHAPE_PROBE, y - normal.y * SHAPE_PROBE, z - normal.z * SHAPE_PROBE);
		return portal.getPortalShape().raytracePortalShape(portal.getThisSideState(), from, to, SHAPE_PROBE) != null;
	}

	private static Vector3d vector(Vec3 v) {
		return new Vector3d(v.x, v.y, v.z);
	}
}
