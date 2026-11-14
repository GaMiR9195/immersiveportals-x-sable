package ipl.sable.render.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One model as its renderer wrote it, kept until the whole model is known and then written on
 * into real buffers: only the parts whose route through the portal openings is the wanted one
 * ({@link IplLinkTopology}). Vertices are kept in the space they arrive in: the renderer's pose
 * stack is already applied, so that is the camera-relative space of the draw.
 *
 * <p>Replay asks the target for each render type once, in the order the renderer first used
 * them, so shared batches of a buffer source receive the model like an ordinary draw.
 * Quads, triangles and lines are routed part by part; other modes (strips, fans) go with the
 * state of the anchor.
 */
final class IplModelRecorder implements MultiBufferSource {

	private final Map<RenderType, Recording> recordings = new LinkedHashMap<>();

	@Override
	public VertexConsumer getBuffer(RenderType type) {
		return recordings.computeIfAbsent(type, t -> new Recording(t.mode()));
	}

	/**
	 * Writes into {@code target} the parts whose state is {@code wanted}, for a model hanging at
	 * {@code anchor} with state {@code anchorState} (bit {@code k}: through opening {@code k}).
	 */
	void replay(MultiBufferSource target, List<IplPortalRect> openings, Vector3d anchor, int anchorState, int wanted) {
		List<IplLinkTopology.Piece> pieces = new ArrayList<>();
		for (Recording r : recordings.values()) r.cut(openings, pieces);
		int[] states = IplLinkTopology.classify(pieces, openings, anchor, anchorState);
		int next = 0;
		for (Map.Entry<RenderType, Recording> entry : recordings.entrySet()) {
			Recording r = entry.getValue();
			VertexConsumer out = null;
			if (r.primitiveSize == 0) {
				if (anchorState == wanted && !r.vertices.isEmpty()) {
					out = target.getBuffer(entry.getKey());
					for (IplVertex v : r.vertices) v.writeTo(out);
				}
				continue;
			}
			for (; next < pieces.size() && pieces.get(next).owner() == r; next++) {
				if (states[next] != wanted) continue;
				if (out == null) out = target.getBuffer(entry.getKey());
				r.emit(out, pieces.get(next).polygon());
			}
		}
	}

	/** The vertices of one render type; {@link #primitiveSize} 0: a mode that is not cut. */
	private static final class Recording implements VertexConsumer {
		final int primitiveSize;
		final List<IplVertex> vertices = new ArrayList<>();
		private IplVertex current = new IplVertex();

		Recording(VertexFormat.Mode mode) {
			this.primitiveSize = switch (mode) {
				case QUADS -> 4;
				case TRIANGLES -> 3;
				case LINES, DEBUG_LINES -> 2;
				default -> 0;
			};
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			current = new IplVertex();
			current.x = x; current.y = y; current.z = z;
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

		/** Complete primitives as cut pieces (a trailing incomplete primitive is dropped). */
		void cut(List<IplPortalRect> openings, List<IplLinkTopology.Piece> out) {
			if (primitiveSize == 0) return;
			boolean polygon = primitiveSize >= 3;
			int end = vertices.size() - vertices.size() % primitiveSize;
			for (int first = 0; first < end; first += primitiveSize) {
				IplLinkTopology.cut(this, first, vertices.subList(first, first + primitiveSize), polygon, openings, out);
			}
		}

		/** A convex piece back as primitives of this mode (quads repeat their last vertex). */
		void emit(VertexConsumer out, List<IplVertex> piece) {
			int n = piece.size();
			if (primitiveSize == 2) {
				piece.get(0).writeTo(out);
				piece.get(n - 1).writeTo(out);
				return;
			}
			if (primitiveSize == 3) {
				for (int i = 1; i + 1 < n; i++) {
					piece.get(0).writeTo(out); piece.get(i).writeTo(out); piece.get(i + 1).writeTo(out);
				}
				return;
			}
			for (int i = 1; i + 1 < n; i += 2) {
				piece.get(0).writeTo(out);
				piece.get(i).writeTo(out);
				piece.get(i + 1).writeTo(out);
				piece.get(i + 2 < n ? i + 2 : i + 1).writeTo(out);
			}
		}
	}
}
