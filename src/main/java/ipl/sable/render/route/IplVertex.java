package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * One recorded vertex in route space with every attribute the renderer set; vertices created
 * on a cut are interpolated.
 */
final class IplVertex {
	double x, y, z;
	boolean hasColor, hasUv, hasUv1, hasUv2, hasNormal;
	int r, g, b, a;
	float u, v;
	int u1, v1, u2, v2;
	float nx, ny, nz;

	static IplVertex lerp(IplVertex p, IplVertex q, double t) {
		IplVertex o = new IplVertex();
		o.x = p.x + (q.x - p.x) * t;
		o.y = p.y + (q.y - p.y) * t;
		o.z = p.z + (q.z - p.z) * t;
		o.hasColor = p.hasColor; o.hasUv = p.hasUv; o.hasUv1 = p.hasUv1; o.hasUv2 = p.hasUv2; o.hasNormal = p.hasNormal;
		o.r = mix(p.r, q.r, t); o.g = mix(p.g, q.g, t); o.b = mix(p.b, q.b, t); o.a = mix(p.a, q.a, t);
		o.u = (float) (p.u + (q.u - p.u) * t);
		o.v = (float) (p.v + (q.v - p.v) * t);
		o.u1 = mix(p.u1, q.u1, t); o.v1 = mix(p.v1, q.v1, t);
		o.u2 = mix(p.u2, q.u2, t); o.v2 = mix(p.v2, q.v2, t);
		o.nx = (float) (p.nx + (q.nx - p.nx) * t);
		o.ny = (float) (p.ny + (q.ny - p.ny) * t);
		o.nz = (float) (p.nz + (q.nz - p.nz) * t);
		return o;
	}

	private static int mix(int p, int q, double t) {
		return (int) Math.round(p + (q - p) * t);
	}

	double distanceSquared(double px, double py, double pz) {
		double dx = x - px, dy = y - py, dz = z - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	Vector3d position() {
		return new Vector3d(x, y, z);
	}

	/** Writes this vertex placed by {@code placement}. */
	void writeTo(VertexConsumer out, Placement placement) {
		Vector3d p = placement.position.transformPosition(x, y, z, placement.scratch);
		out.addVertex((float) p.x, (float) p.y, (float) p.z);
		if (hasColor) out.setColor(r, g, b, a);
		if (hasUv) out.setUv(u, v);
		if (hasUv1) out.setUv1(u1, v1);
		if (hasUv2) out.setUv2(u2, v2);
		if (hasNormal) {
			Vector3d n = placement.normal.transform(nx, ny, nz, placement.scratch);
			double length = n.length();
			if (length > 1.0e-6) n.div(length);
			out.setNormal((float) n.x, (float) n.y, (float) n.z);
		}
	}

	/** Route space → camera-relative space of a draw, and its rotation for normals. */
	static final class Placement {
		final Matrix4d position;
		final Matrix3d normal;
		private final Vector3d scratch = new Vector3d();

		Placement(Matrix4d position) {
			this.position = position;
			this.normal = position.get3x3(new Matrix3d());
		}
	}
}
