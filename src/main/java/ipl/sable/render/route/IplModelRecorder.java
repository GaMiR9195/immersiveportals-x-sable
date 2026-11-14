package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One model as its renderer wrote it, kept whole in route space. The renderer draws relative to
 * {@code origin} (small float coordinates); vertices are stored absolute, in doubles. Render
 * types keep the order of their first use, so replaying asks a buffer source for each type the
 * way the renderer did.
 */
final class IplModelRecorder implements MultiBufferSource {

	private final Vector3dc origin;
	private final Map<RenderType, Recording> recordings = new LinkedHashMap<>();

	IplModelRecorder(Vector3dc origin) {
		this.origin = origin;
	}

	@Override
	public VertexConsumer getBuffer(RenderType type) {
		return recordings.computeIfAbsent(type, t -> new Recording(t, origin));
	}

	List<Recording> recordings() {
		return new ArrayList<>(recordings.values());
	}

	/** Bounds of every recorded vertex; null when nothing was written. */
	@Nullable
	AABB bounds() {
		double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
		double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		for (Recording r : recordings.values()) {
			for (IplVertex v : r.vertices) {
				minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
				maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
			}
		}
		return minX > maxX ? null : new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** The vertices of one render type; {@link #primitiveSize} 0: a mode whose primitives are not cut (strips, fans). */
	static final class Recording implements VertexConsumer {
		final RenderType type;
		final int primitiveSize;
		final List<IplVertex> vertices = new ArrayList<>();
		private final Vector3dc origin;
		private IplVertex current = new IplVertex();

		Recording(RenderType type, Vector3dc origin) {
			this.type = type;
			this.origin = origin;
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

		/** Number of complete primitives (a trailing incomplete one is never drawn). */
		int primitives() {
			return primitiveSize == 0 ? 0 : vertices.size() / primitiveSize;
		}

		List<IplVertex> primitive(int index) {
			return vertices.subList(index * primitiveSize, (index + 1) * primitiveSize);
		}

		/** Writes a convex part back as primitives of this mode (quads repeat their last vertex). */
		void emit(VertexConsumer out, List<IplVertex> part, IplVertex.Placement placement) {
			int n = part.size();
			if (primitiveSize == 2) {
				part.get(0).writeTo(out, placement);
				part.get(n - 1).writeTo(out, placement);
				return;
			}
			if (primitiveSize == 3) {
				for (int i = 1; i + 1 < n; i++) {
					part.get(0).writeTo(out, placement);
					part.get(i).writeTo(out, placement);
					part.get(i + 1).writeTo(out, placement);
				}
				return;
			}
			for (int i = 1; i + 1 < n; i += 2) {
				part.get(0).writeTo(out, placement);
				part.get(i).writeTo(out, placement);
				part.get(i + 1).writeTo(out, placement);
				part.get(i + 2 < n ? i + 2 : i + 1).writeTo(out, placement);
			}
		}

		/** Writes every recorded vertex unchanged in shape (a model with one frame). */
		void emitAll(VertexConsumer out, IplVertex.Placement placement) {
			int end = primitiveSize == 0 ? vertices.size() : primitives() * primitiveSize;
			for (int i = 0; i < end; i++) vertices.get(i).writeTo(out, placement);
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			current = new IplVertex();
			current.x = origin.x() + x;
			current.y = origin.y() + y;
			current.z = origin.z() + z;
			vertices.add(current);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			current.hasColor = true; current.r = r; current.g = g; current.b = b; current.a = a;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			current.hasUv = true; current.u = u; current.v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			current.hasUv1 = true; current.u1 = u; current.v1 = v;
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			current.hasUv2 = true; current.u2 = u; current.v2 = v;
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			current.hasNormal = true; current.nx = x; current.ny = y; current.nz = z;
			return this;
		}
	}
}
