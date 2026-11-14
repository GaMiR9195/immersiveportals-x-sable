package ipl.sable.render.route;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * A portal rectangle in the space a model is recorded in (camera-relative world of the pass).
 * {@link #normal} points to the entry side; the other side of the plane is where the passed
 * material of the straddling body is.
 */
record IplPortalRect(Vector3d origin, Vector3d normal, Vector3d u, double halfU, Vector3d v, double halfV) {

	/** Lateral tolerance: an edge through the frame's rim still counts as passing the opening. */
	private static final double RIM = 1.0e-3;

	/** The world rectangle {@code (origin, normal, u, v)}, optionally through {@code portal}, relative to {@code camera}. */
	static IplPortalRect of(Vector3d origin, Vector3d normal, Vector3d u, double halfU, Vector3d v, double halfV,
							@Nullable Matrix4d portal, Vector3d camera) {
		Vector3d o = new Vector3d(origin);
		Vector3d n = new Vector3d(normal);
		Vector3d mu = new Vector3d(u);
		Vector3d mv = new Vector3d(v);
		if (portal != null) {
			portal.transformPosition(o);
			portal.transformDirection(n);
			portal.transformDirection(mu);
			portal.transformDirection(mv);
		}
		// A scaling portal scales the half extents with the axes.
		double su = mu.length(), sv = mv.length();
		return new IplPortalRect(o.sub(camera), n.normalize(), mu.div(su), halfU * su, mv.div(sv), halfV * sv);
	}

	double side(double x, double y, double z) {
		return (x - origin.x) * normal.x + (y - origin.y) * normal.y + (z - origin.z) * normal.z;
	}

	double side(Vector3d p) {
		return side(p.x, p.y, p.z);
	}

	/** Whether the segment {@code p → q} passes the plane inside the rectangle. */
	boolean passedBy(double px, double py, double pz, double qx, double qy, double qz) {
		double dp = side(px, py, pz), dq = side(qx, qy, qz);
		if ((dp >= 0.0) == (dq >= 0.0)) return false;
		double t = dp / (dp - dq);
		double x = px + (qx - px) * t - origin.x;
		double y = py + (qy - py) * t - origin.y;
		double z = pz + (qz - pz) * t - origin.z;
		return Math.abs(x * u.x + y * u.y + z * u.z) <= halfU + RIM
			&& Math.abs(x * v.x + y * v.y + z * v.z) <= halfV + RIM;
	}

	/** Half-space {@code a·x + b·y + c·z + d >= 0} of the entry side. */
	double[] entrySide() {
		return new double[]{normal.x, normal.y, normal.z, -normal.dot(origin)};
	}

	/** Half-space of the passed side. */
	double[] passedSide() {
		return new double[]{-normal.x, -normal.y, -normal.z, normal.dot(origin)};
	}
}
