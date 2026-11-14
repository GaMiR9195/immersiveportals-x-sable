package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One model as its renderer wrote it, in route space. The renderer draws relative to
 * {@link #ox origin} (small float coordinates, exactly what it would have written into a buffer);
 * vertices are kept that way in flat arrays, so recording allocates nothing per vertex and a
 * recorder is reused frame after frame by the model's {@link IplRouteMemory}. Render types keep
 * the order of their first use, so replaying asks a buffer source for each type the way the
 * renderer did.
 */
final class IplModelRecorder implements MultiBufferSource {

	/** Render types kept for reuse beyond this are dropped (a renderer cycling through textures). */
	private static final int POOL_LIMIT = 64;

	double ox, oy, oz;
	private final Map<RenderType, Recording> pool = new HashMap<>();
	private final List<Recording> order = new ArrayList<>();
	@Nullable private Recording last;

	/** Starts a new recording at {@code origin}; the arrays of the previous one are reused. */
	void reset(Vector3dc origin) {
		ox = origin.x();
		oy = origin.y();
		oz = origin.z();
		for (Recording r : order) r.clear();
		order.clear();
		last = null;
		if (pool.size() > POOL_LIMIT) pool.clear();
	}

	@Override
	public VertexConsumer getBuffer(RenderType type) {
		Recording r = last;
		if (r != null && r.type == type) return r;
		r = pool.get(type);
		if (r == null) {
			r = new Recording(type);
			pool.put(type, r);
		}
		if (!r.active) {
			r.active = true;
			order.add(r);
		}
		last = r;
		return r;
	}

	/** The recordings in order of first use (live view, valid until the next {@link #reset}). */
	List<Recording> recordings() {
		return order;
	}

	/** Bounds of every recorded vertex in route space; null when nothing was written. */
	@Nullable
	AABB bounds() {
		float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
		float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		for (Recording r : order) {
			float[] p = r.pos;
			for (int i = 0, n = r.count * 3; i < n; i += 3) {
				float x = p[i], y = p[i + 1], z = p[i + 2];
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				if (y < minY) minY = y;
				if (y > maxY) maxY = y;
				if (z < minZ) minZ = z;
				if (z > maxZ) maxZ = z;
			}
		}
		return minX > maxX ? null : new AABB(ox + minX, oy + minY, oz + minZ, ox + maxX, oy + maxY, oz + maxZ);
	}

	// ------------------------------------------------------------------ placement

	/**
	 * Route space → camera-relative space of a draw, for vertices stored relative to the origin:
	 * {@code linear · local + offset}. The common case — a part drawn in its own level — is a pure
	 * offset and costs three additions per vertex.
	 */
	static final class Placement {
		final boolean identity;
		final boolean unitNormals;
		final float m00, m01, m02, m10, m11, m12, m20, m21, m22;
		final float tx, ty, tz;

		Placement(Matrix4d toLevel, double camX, double camY, double camZ, double ox, double oy, double oz) {
			Matrix4d m = new Matrix4d().translation(-camX, -camY, -camZ).mul(toLevel);
			m00 = (float) m.m00(); m01 = (float) m.m10(); m02 = (float) m.m20();
			m10 = (float) m.m01(); m11 = (float) m.m11(); m12 = (float) m.m21();
			m20 = (float) m.m02(); m21 = (float) m.m12(); m22 = (float) m.m22();
			tx = (float) (m.m00() * ox + m.m10() * oy + m.m20() * oz + m.m30());
			ty = (float) (m.m01() * ox + m.m11() * oy + m.m21() * oz + m.m31());
			tz = (float) (m.m02() * ox + m.m12() * oy + m.m22() * oz + m.m32());
			identity = m00 == 1f && m11 == 1f && m22 == 1f && m01 == 0f && m02 == 0f && m10 == 0f && m12 == 0f && m20 == 0f && m21 == 0f;
			double c0 = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
			unitNormals = identity || Math.abs(c0 - 1.0) < 1.0e-6;
		}
	}

	// ------------------------------------------------------------------ recording

	/** The vertices of one render type; {@link #primitiveSize} 0: a mode whose primitives are not cut (strips, fans). */
	static final class Recording implements VertexConsumer {
		static final byte COLOR = 1, UV = 2, UV1 = 4, UV2 = 8, NORMAL = 16;
		static final byte ALL = COLOR | UV | UV1 | UV2 | NORMAL;

		final RenderType type;
		final int primitiveSize;
		/** Vertices the renderer wrote. */
		int count;
		/** Written plus those created by cuts (appended after {@link #count}). */
		int size;
		float[] pos = new float[48];
		int[] color = new int[16];
		float[] uv = new float[32];
		int[] uv1 = new int[16];
		int[] uv2 = new int[16];
		float[] normal = new float[48];
		byte[] flags = new byte[16];
		boolean active;
		private int current = -1;

		/** Routing of this frame: per primitive its frame index, or {@link IplRouteSolver#CUT}. */
		int[] primFrame = new int[0];
		/** Cut pieces in primitive order: {@code prim, frame, n, n vertex indices}. */
		int[] pieces = new int[0];
		int piecesSize;

		Recording(RenderType type) {
			this.type = type;
			this.primitiveSize = primitiveSize(type.mode());
		}

		private static int primitiveSize(VertexFormat.Mode mode) {
			return switch (mode) {
				case QUADS -> 4;
				case TRIANGLES -> 3;
				case LINES, DEBUG_LINES -> 2;
				default -> 0;
			};
		}

		void clear() {
			count = 0;
			size = 0;
			current = -1;
			active = false;
			piecesSize = 0;
		}

		/** Number of complete primitives (a trailing incomplete one is never drawn). */
		int primitives() {
			return primitiveSize == 0 ? 0 : count / primitiveSize;
		}

		/** Units the router labels: the primitives, or the whole recording for strips and fans. */
		int units() {
			return primitiveSize == 0 ? (count > 0 ? 1 : 0) : primitives();
		}

		private int grow() {
			int i = size;
			if (i == flags.length) {
				int n = Math.max(16, i * 2);
				pos = Arrays.copyOf(pos, n * 3);
				color = Arrays.copyOf(color, n);
				uv = Arrays.copyOf(uv, n * 2);
				uv1 = Arrays.copyOf(uv1, n);
				uv2 = Arrays.copyOf(uv2, n);
				normal = Arrays.copyOf(normal, n * 3);
				flags = Arrays.copyOf(flags, n);
			}
			size = i + 1;
			return i;
		}

		/** Appends the vertex between {@code p} and {@code q} at {@code t}; returns its index. */
		int lerp(int p, int q, double t) {
			int o = grow();
			float ft = (float) t;
			int p3 = p * 3, q3 = q * 3, o3 = o * 3;
			pos[o3] = (float) (pos[p3] + (pos[q3] - pos[p3]) * t);
			pos[o3 + 1] = (float) (pos[p3 + 1] + (pos[q3 + 1] - pos[p3 + 1]) * t);
			pos[o3 + 2] = (float) (pos[p3 + 2] + (pos[q3 + 2] - pos[p3 + 2]) * t);
			byte f = flags[p];
			flags[o] = f;
			if ((f & COLOR) != 0) color[o] = mixColor(color[p], color[q], t);
			if ((f & UV) != 0) {
				uv[o * 2] = uv[p * 2] + (uv[q * 2] - uv[p * 2]) * ft;
				uv[o * 2 + 1] = uv[p * 2 + 1] + (uv[q * 2 + 1] - uv[p * 2 + 1]) * ft;
			}
			if ((f & UV1) != 0) uv1[o] = mixShorts(uv1[p], uv1[q], t);
			if ((f & UV2) != 0) uv2[o] = mixShorts(uv2[p], uv2[q], t);
			if ((f & NORMAL) != 0) {
				normal[o3] = normal[p3] + (normal[q3] - normal[p3]) * ft;
				normal[o3 + 1] = normal[p3 + 1] + (normal[q3 + 1] - normal[p3 + 1]) * ft;
				normal[o3 + 2] = normal[p3 + 2] + (normal[q3 + 2] - normal[p3 + 2]) * ft;
			}
			return o;
		}

		private static int mixColor(int p, int q, double t) {
			int a = mix(p >>> 24, q >>> 24, t), r = mix(p >> 16 & 0xFF, q >> 16 & 0xFF, t);
			int g = mix(p >> 8 & 0xFF, q >> 8 & 0xFF, t), b = mix(p & 0xFF, q & 0xFF, t);
			return a << 24 | r << 16 | g << 8 | b;
		}

		private static int mixShorts(int p, int q, double t) {
			return mix(p & 0xFFFF, q & 0xFFFF, t) & 0xFFFF | mix(p >>> 16, q >>> 16, t) << 16;
		}

		private static int mix(int p, int q, double t) {
			return (int) Math.round(p + (q - p) * t);
		}

		// -------------------------------------------------------------- replay

		/** Writes vertex {@code i} placed by {@code pl}. */
		void write(VertexConsumer out, int i, Placement pl) {
			int i3 = i * 3;
			float x = pos[i3], y = pos[i3 + 1], z = pos[i3 + 2];
			float px, py, pz;
			if (pl.identity) {
				px = x + pl.tx; py = y + pl.ty; pz = z + pl.tz;
			} else {
				px = pl.m00 * x + pl.m01 * y + pl.m02 * z + pl.tx;
				py = pl.m10 * x + pl.m11 * y + pl.m12 * z + pl.ty;
				pz = pl.m20 * x + pl.m21 * y + pl.m22 * z + pl.tz;
			}
			byte f = flags[i];
			if ((f & NORMAL) == 0) {
				out.addVertex(px, py, pz);
				if ((f & COLOR) != 0) out.setColor(color[i]);
				if ((f & UV) != 0) out.setUv(uv[i * 2], uv[i * 2 + 1]);
				if ((f & UV1) != 0) out.setUv1(uv1[i] & 0xFFFF, uv1[i] >>> 16);
				if ((f & UV2) != 0) out.setUv2(uv2[i] & 0xFFFF, uv2[i] >>> 16);
				return;
			}
			float nx = normal[i3], ny = normal[i3 + 1], nz = normal[i3 + 2];
			if (!pl.identity) {
				float tx = pl.m00 * nx + pl.m01 * ny + pl.m02 * nz;
				float ty = pl.m10 * nx + pl.m11 * ny + pl.m12 * nz;
				float tz = pl.m20 * nx + pl.m21 * ny + pl.m22 * nz;
				nx = tx; ny = ty; nz = tz;
				if (!pl.unitNormals) {
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len > 1.0e-6f) { nx /= len; ny /= len; nz /= len; }
				}
			}
			if (f == ALL) {
				out.addVertex(px, py, pz, color[i], uv[i * 2], uv[i * 2 + 1], uv1[i], uv2[i], nx, ny, nz);
				return;
			}
			out.addVertex(px, py, pz);
			if ((f & COLOR) != 0) out.setColor(color[i]);
			if ((f & UV) != 0) out.setUv(uv[i * 2], uv[i * 2 + 1]);
			if ((f & UV1) != 0) out.setUv1(uv1[i] & 0xFFFF, uv1[i] >>> 16);
			if ((f & UV2) != 0) out.setUv2(uv2[i] & 0xFFFF, uv2[i] >>> 16);
			out.setNormal(nx, ny, nz);
		}

		/** Writes recorded primitives {@code [from, to)} unchanged in shape. */
		void writePrimitives(VertexConsumer out, int from, int to, Placement pl) {
			for (int i = from * primitiveSize, end = to * primitiveSize; i < end; i++) write(out, i, pl);
		}

		/** Writes every recorded vertex unchanged in shape (a recording in one frame). */
		void writeAll(VertexConsumer out, Placement pl) {
			int end = primitiveSize == 0 ? count : primitives() * primitiveSize;
			for (int i = 0; i < end; i++) write(out, i, pl);
		}

		/** Writes a convex cut part ({@code n} vertex indices at {@code at} in {@code idx}) as primitives of this mode. */
		void writePart(VertexConsumer out, int[] idx, int at, int n, Placement pl) {
			if (primitiveSize == 2) {
				write(out, idx[at], pl);
				write(out, idx[at + n - 1], pl);
				return;
			}
			if (primitiveSize == 3) {
				for (int i = 1; i + 1 < n; i++) {
					write(out, idx[at], pl);
					write(out, idx[at + i], pl);
					write(out, idx[at + i + 1], pl);
				}
				return;
			}
			for (int i = 1; i + 1 < n; i += 2) {
				write(out, idx[at], pl);
				write(out, idx[at + i], pl);
				write(out, idx[at + i + 1], pl);
				write(out, idx[at + (i + 2 < n ? i + 2 : i + 1)], pl);
			}
		}

		// -------------------------------------------------------------- VertexConsumer

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			int i = grow();
			count = size;
			int i3 = i * 3;
			pos[i3] = x;
			pos[i3 + 1] = y;
			pos[i3 + 2] = z;
			flags[i] = 0;
			current = i;
			return this;
		}

		@Override
		public void addVertex(float x, float y, float z, int argb, float u, float v, int overlay, int light,
							  float nx, float ny, float nz) {
			int i = grow();
			count = size;
			int i3 = i * 3;
			pos[i3] = x;
			pos[i3 + 1] = y;
			pos[i3 + 2] = z;
			color[i] = argb;
			uv[i * 2] = u;
			uv[i * 2 + 1] = v;
			uv1[i] = overlay;
			uv2[i] = light;
			normal[i3] = nx;
			normal[i3 + 1] = ny;
			normal[i3 + 2] = nz;
			flags[i] = ALL;
			current = i;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			if (current < 0) return this;
			color[current] = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | b & 0xFF;
			flags[current] |= COLOR;
			return this;
		}

		@Override
		public VertexConsumer setColor(int argb) {
			if (current < 0) return this;
			color[current] = argb;
			flags[current] |= COLOR;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			if (current < 0) return this;
			uv[current * 2] = u;
			uv[current * 2 + 1] = v;
			flags[current] |= UV;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			if (current < 0) return this;
			uv1[current] = u & 0xFFFF | v << 16;
			flags[current] |= UV1;
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			if (current < 0) return this;
			uv2[current] = u & 0xFFFF | v << 16;
			flags[current] |= UV2;
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			if (current < 0) return this;
			int i3 = current * 3;
			normal[i3] = x;
			normal[i3 + 1] = y;
			normal[i3 + 2] = z;
			flags[current] |= NORMAL;
			return this;
		}
	}
}
