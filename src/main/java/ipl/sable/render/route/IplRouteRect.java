package ipl.sable.render.route;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.List;

/**
 * An opening placed in route space: the world space of the level a model is recorded in, where a
 * deeper frame's portal appears through the inverse of the frame's map. All tests are finite:
 * a plane is only passed inside the rectangle (plus {@link #RIM}), and non-rectangular shapes are
 * refined by the portal's own shape.
 */
final class IplRouteRect {

	/** Lateral tolerance: an edge through the frame's rim still passes the opening. */
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

	double side(IplVertex p) {
		return side(p.x, p.y, p.z);
	}

	/** Whether a point of the plane lies in the opening. */
	boolean containsOnPlane(double x, double y, double z) {
		double dx = x - origin.x, dy = y - origin.y, dz = z - origin.z;
		if (Math.abs(dx * u.x + dy * u.y + dz * u.z) > halfU + RIM) return false;
		if (Math.abs(dx * v.x + dy * v.y + dz * v.z) > halfV + RIM) return false;
		return opening.rectangular || opening.shapeContains(toLevel.transformPosition(new Vector3d(x, y, z)));
	}

	/**
	 * Where the segment {@code p → q} passes the opening, as a fraction of the segment, or
	 * {@code NaN} when it does not cross the plane inside the opening.
	 */
	double passage(double px, double py, double pz, double qx, double qy, double qz) {
		double dp = side(px, py, pz), dq = side(qx, qy, qz);
		if ((dp >= 0.0) == (dq >= 0.0)) return Double.NaN;
		double t = dp / (dp - dq);
		return containsOnPlane(px + (qx - px) * t, py + (qy - py) * t, pz + (qz - pz) * t) ? t : Double.NaN;
	}

	/**
	 * Whether the plane section of a convex primitive (the chord between its two plane
	 * crossings {@code a → b}) overlaps the opening.
	 */
	boolean chordOverlaps(Vector3d a, Vector3d b) {
		double au = local(a, u), av = local(a, v);
		double du = local(b, u) - au, dv = local(b, v) - av;
		double[] range = {0.0, 1.0};
		if (!clipRange(du, -halfU - RIM - au, range) || !clipRange(-du, -halfU - RIM + au, range)) return false;
		if (!clipRange(dv, -halfV - RIM - av, range) || !clipRange(-dv, -halfV - RIM + av, range)) return false;
		if (opening.rectangular) return true;
		double t = (range[0] + range[1]) * 0.5;
		return opening.shapeContains(toLevel.transformPosition(new Vector3d(b).sub(a).mul(t).add(a)));
	}

	/** Liang–Barsky step for the constraint {@code d·t >= limit} on {@code t ∈ range}. */
	private static boolean clipRange(double d, double limit, double[] range) {
		if (d == 0.0) return limit <= 0.0;
		double t = limit / d;
		if (d > 0.0) range[0] = Math.max(range[0], t);
		else range[1] = Math.min(range[1], t);
		return range[0] <= range[1];
	}

	private double local(Vector3d p, Vector3d axis) {
		return (p.x - origin.x) * axis.x + (p.y - origin.y) * axis.y + (p.z - origin.z) * axis.z;
	}

	/** Whether both are one rectangle (either facing): one plane, one area. */
	boolean coincides(IplRouteRect other) {
		if (!coplanar(other) || origin.distanceSquared(other.origin) > COPLANAR_DISTANCE) return false;
		if (Math.abs(u.dot(other.u)) > COPLANAR_COS) {
			return Math.abs(halfU - other.halfU) < SIZE_EPSILON && Math.abs(halfV - other.halfV) < SIZE_EPSILON;
		}
		return Math.abs(u.dot(other.v)) > COPLANAR_COS
			&& Math.abs(halfU - other.halfV) < SIZE_EPSILON && Math.abs(halfV - other.halfU) < SIZE_EPSILON;
	}

	/** Whether every vertex lies over the opening (between its edge planes, plus {@link #RIM}). */
	boolean overOpening(List<IplVertex> vertices) {
		for (IplVertex p : vertices) {
			double dx = p.x - origin.x, dy = p.y - origin.y, dz = p.z - origin.z;
			if (Math.abs(dx * u.x + dy * u.y + dz * u.z) > halfU + RIM) return false;
			if (Math.abs(dx * v.x + dy * v.y + dz * v.z) > halfV + RIM) return false;
		}
		return true;
	}

	/** Whether both rectangles lie in one plane (either facing). */
	boolean coplanar(IplRouteRect other) {
		return Math.abs(normal.dot(other.normal)) > COPLANAR_COS
			&& Math.abs(side(other.origin.x, other.origin.y, other.origin.z)) < COPLANAR_DISTANCE;
	}

	/**
	 * Whether the plane cuts through {@code box} where the box comes within {@code margin} of the
	 * opening: geometry in the box may be on both sides of this opening.
	 */
	boolean straddledBy(AABB box, double margin) {
		if (!near(box, margin)) return false;
		double cx = (box.minX + box.maxX) * 0.5, cy = (box.minY + box.maxY) * 0.5, cz = (box.minZ + box.maxZ) * 0.5;
		double ex = (box.maxX - box.minX) * 0.5, ey = (box.maxY - box.minY) * 0.5, ez = (box.maxZ - box.minZ) * 0.5;
		return Math.abs(side(cx, cy, cz)) <= support(normal, ex, ey, ez);
	}

	/** Which side of the plane {@code box} lies on: 1 front, -1 back, 0 cut by the plane. */
	int sideOf(AABB box) {
		double cx = (box.minX + box.maxX) * 0.5, cy = (box.minY + box.maxY) * 0.5, cz = (box.minZ + box.maxZ) * 0.5;
		double r = support(normal, (box.maxX - box.minX) * 0.5, (box.maxY - box.minY) * 0.5, (box.maxZ - box.minZ) * 0.5);
		double d = side(cx, cy, cz);
		return d > r ? 1 : d < -r ? -1 : 0;
	}

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

	/** The entry side of the plane. */
	double[] frontSpace() {
		return new double[]{normal.x, normal.y, normal.z, -normal.dot(origin)};
	}

	/** The passed side of the plane. */
	double[] backSpace() {
		return new double[]{-normal.x, -normal.y, -normal.z, normal.dot(origin)};
	}

	/** The four half-spaces beside the rectangle (outside each of its edges). */
	double[][] besideSpaces() {
		return new double[][]{
			outside(u, halfU, 1.0), outside(u, halfU, -1.0), outside(v, halfV, 1.0), outside(v, halfV, -1.0)
		};
	}

	private double[] outside(Vector3d axis, double half, double sign) {
		double ax = axis.x * sign, ay = axis.y * sign, az = axis.z * sign;
		return new double[]{ax, ay, az, -(ax * origin.x + ay * origin.y + az * origin.z) - (half + RIM)};
	}
}
