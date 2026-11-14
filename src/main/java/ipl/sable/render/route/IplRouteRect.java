package ipl.sable.render.route;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * An opening placed in route space: the world space of the level a model is recorded in, where a
 * deeper frame's portal appears through the inverse of the frame's map. All tests are finite:
 * the passage of a face is the region behind its plane and over its rectangle (plus {@link #RIM}),
 * and non-rectangular shapes are refined by the portal's own shape.
 */
final class IplRouteRect {

	/** Lateral tolerance: geometry this far past the rim still passes the opening. */
	static final double RIM = 1.0e-3;
	private static final double COPLANAR_COS = 0.9999;
	private static final double COPLANAR_DISTANCE = 1.0e-4;
	private static final double SIZE_EPSILON = 1.0e-3;

	final IplOpening opening;
	/** The frame's map from route space into the opening's level (shape refinement only). */
	private final Matrix4d toLevel;
	final Vector3d origin;
	final Vector3d normal;
	final Vector3d u;
	final Vector3d v;
	final double halfU;
	final double halfV;

	private IplRouteRect(IplOpening opening, Matrix4d toLevel, Vector3d origin, Vector3d normal,
						 Vector3d u, double halfU, Vector3d v, double halfV) {
		this.opening = opening;
		this.toLevel = toLevel;
		this.origin = origin;
		this.normal = normal;
		this.u = u;
		this.halfU = halfU;
		this.v = v;
		this.halfV = halfV;
	}

	/** {@code opening} (in its level's world space) seen in route space through {@code fromLevel}. */
	static IplRouteRect of(IplOpening opening, Matrix4d toLevel, Matrix4d fromLevel) {
		Vector3d o = fromLevel.transformPosition(new Vector3d(opening.origin));
		Vector3d n = fromLevel.transformDirection(new Vector3d(opening.normal)).normalize();
		Vector3d mu = fromLevel.transformDirection(new Vector3d(opening.axisW));
		Vector3d mv = fromLevel.transformDirection(new Vector3d(opening.axisH));
		// A scaling frame scales the half extents with the axes.
		double su = mu.length(), sv = mv.length();
		return new IplRouteRect(opening, toLevel, o, n, mu.div(su), opening.halfW * su, mv.div(sv), opening.halfH * sv);
	}

	// ------------------------------------------------------------------ point tests

	/** Signed distance to the plane, positive on the entry side. */
	double side(double x, double y, double z) {
		return (x - origin.x) * normal.x + (y - origin.y) * normal.y + (z - origin.z) * normal.z;
	}

	/** Whether a point of the plane lies in the opening (rectangle plus {@link #RIM}, then the portal's shape). */
	boolean containsOnPlane(double x, double y, double z) {
		double dx = x - origin.x, dy = y - origin.y, dz = z - origin.z;
		if (Math.abs(dx * u.x + dy * u.y + dz * u.z) > halfU + RIM) return false;
		if (Math.abs(dx * v.x + dy * v.y + dz * v.z) > halfV + RIM) return false;
		return opening.rectangular || opening.shapeContains(toLevel.transformPosition(new Vector3d(x, y, z)));
	}

	/** Whether a point lies over the opening: its projection along the normal is in the opening. */
	boolean over(double x, double y, double z) {
		double s = side(x, y, z);
		return containsOnPlane(x - normal.x * s, y - normal.y * s, z - normal.z * s);
	}

	// ------------------------------------------------------------------ pair tests

	/** Whether both are one rectangle (either facing): one plane, one area. */
	boolean coincides(IplRouteRect other) {
		if (!coplanar(other) || origin.distanceSquared(other.origin) > COPLANAR_DISTANCE) return false;
		if (Math.abs(u.dot(other.u)) > COPLANAR_COS) {
			return Math.abs(halfU - other.halfU) < SIZE_EPSILON && Math.abs(halfV - other.halfV) < SIZE_EPSILON;
		}
		return Math.abs(u.dot(other.v)) > COPLANAR_COS
			&& Math.abs(halfU - other.halfV) < SIZE_EPSILON && Math.abs(halfV - other.halfU) < SIZE_EPSILON;
	}

	/** Whether both rectangles lie in one plane (either facing). */
	boolean coplanar(IplRouteRect other) {
		return Math.abs(normal.dot(other.normal)) > COPLANAR_COS
			&& Math.abs(side(other.origin.x, other.origin.y, other.origin.z)) < COPLANAR_DISTANCE;
	}

	// ------------------------------------------------------------------ box tests

	/** Whether {@code box} comes within {@code margin} of the opening (separating axes of the rectangle). */
	boolean near(AABB box, double margin) {
		double cx = (box.minX + box.maxX) * 0.5 - origin.x;
		double cy = (box.minY + box.maxY) * 0.5 - origin.y;
		double cz = (box.minZ + box.maxZ) * 0.5 - origin.z;
		double ex = (box.maxX - box.minX) * 0.5, ey = (box.maxY - box.minY) * 0.5, ez = (box.maxZ - box.minZ) * 0.5;
		return Math.abs(cx * normal.x + cy * normal.y + cz * normal.z) <= support(normal, ex, ey, ez) + margin
			&& Math.abs(cx * u.x + cy * u.y + cz * u.z) <= halfU + support(u, ex, ey, ez) + margin
			&& Math.abs(cx * v.x + cy * v.y + cz * v.z) <= halfV + support(v, ex, ey, ez) + margin;
	}

	private static double support(Vector3d axis, double ex, double ey, double ez) {
		return ex * Math.abs(axis.x) + ey * Math.abs(axis.y) + ez * Math.abs(axis.z);
	}

	// ------------------------------------------------------------------ half-spaces {a, b, c, d}: a·x + b·y + c·z + d >= 0

	/**
	 * The passage of this face as five half-spaces: behind the plane, and inside each of the four
	 * edge planes of the rectangle (the opening extruded along its normal). Convex, so a primitive
	 * is cut by it exactly and every part lies wholly inside or outside.
	 */
	double[][] passageRegion() {
		double[][] out = new double[5][];
		out[0] = new double[]{-normal.x, -normal.y, -normal.z, normal.dot(origin)};
		out[1] = inside(u, halfU, 1.0);
		out[2] = inside(u, halfU, -1.0);
		out[3] = inside(v, halfV, 1.0);
		out[4] = inside(v, halfV, -1.0);
		return out;
	}

	/** Inside the edge at {@code sign * half} along {@code axis}: {@code sign·axis·(x - o) <= half + RIM}. */
	private double[] inside(Vector3d axis, double half, double sign) {
		double ax = -axis.x * sign, ay = -axis.y * sign, az = -axis.z * sign;
		return new double[]{ax, ay, az, -(ax * origin.x + ay * origin.y + az * origin.z) + half + RIM};
	}
}
