package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** One recorded vertex with every attribute the renderer set; cut vertices are interpolated. */
final class IplVertex {
	double x, y, z;
	boolean hasColor, hasUv, hasUv1, hasUv2, hasNormal;
	int r, g, b, a;
	float u, v;
	int u1, v1, u2, v2;
	float nx, ny, nz;
	/** Written by the renderer; false for a vertex created on a cut. */
	boolean original = true;

	static IplVertex lerp(IplVertex p, IplVertex q, double t) {
		IplVertex o = new IplVertex();
		o.original = false;
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

	void writeTo(VertexConsumer out) {
		out.addVertex((float) x, (float) y, (float) z);
		if (hasColor) out.setColor(r, g, b, a);
		if (hasUv) out.setUv(u, v);
		if (hasUv1) out.setUv1(u1, v1);
		if (hasUv2) out.setUv2(u2, v2);
		if (hasNormal) {
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1e-6f) out.setNormal(nx / len, ny / len, nz / len);
			else out.setNormal(nx, ny, nz);
		}
	}
}
