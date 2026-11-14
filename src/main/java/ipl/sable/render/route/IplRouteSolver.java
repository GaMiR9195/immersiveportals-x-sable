package ipl.sable.render.route;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which frame each part of a recorded model is drawn in. The rule is local and deterministic: a
 * part's frame depends only on where the part is, never on how the model is split into pieces,
 * on the order its renderer wrote it or on a search over its surface, so it cannot flicker from
 * one frame to the next and costs a few plane tests per vertex.
 *
 * <p>Every physical cut (the coincident faces of one opening, {@link IplRouteCut}) is passed
 * through ONE face, chosen by the block the model hangs on:
 * <ol>
 *   <li>the face its block already passed (the anchor's frame is that face's far frame);</li>
 *   <li>else the face whose front holds the anchor. Both faces of a two-sided portal lie in one
 *       plane, and the side the block is on decides, so a model never takes the opposite face
 *       and never shows up on the far side of the exit;</li>
 *   <li>a face of a deeper frame (nested portals, dimension stacking): the face whose front holds
 *       the point where that frame is entered.</li>
 * </ol>
 * Only faces that lead forward count; the exit face of a pair, seen through its entry, is the
 * same plane as the entry and adds nothing.
 * The face splits space in two. Its <em>passage</em> — behind its plane and over its opening (the
 * opening extruded along its normal) — is in the face's far frame; everything else, in front of
 * the plane or beside the rim on either side, is in its near frame. Material beside a frame never
 * passes it, exactly like the voxels of a body, so whatever sticks out past the rim of a portal
 * standing in the air keeps being drawn where it is, and a part's frame never depends on how
 * close the rest of the model is to the rim. Primitives are cut exactly at the passage's five
 * planes, and only where they reach across one.
 */
final class IplRouteSolver {

	/** Distance from a plane below which a vertex counts as lying on it (no slivers). */
	private static final double ON_PLANE = 1.0e-7;
	private static final int INSIDE = 1, OUTSIDE = -1, ACROSS = 0;

	/** One convex part of a recorded primitive, or a whole uncut recording (polygon null), and its frame. */
	static final class Part {
		final int recording;
		@Nullable final List<IplVertex> polygon;
		final IplRouteFrame frame;

		Part(int recording, @Nullable List<IplVertex> polygon, IplRouteFrame frame) {
			this.recording = recording;
			this.polygon = polygon;
			this.frame = frame;
		}
	}

	/**
	 * The parts of a model in recording order, or — when nothing is cut and everything lies in one
	 * frame — just that frame ({@code parts} null): the model is then written as it was recorded.
	 */
	record Result(@Nullable List<Part> parts, @Nullable IplRouteFrame uniform) {}

	/** The face a cut is passed through: its passage is {@code inner}, the rest {@code outer}. */
	private record Passage(IplRouteRect face, double[][] region, IplRouteFrame outer, IplRouteFrame inner) {
		boolean contains(double x, double y, double z) {
			for (double[] h : region) if (h[0] * x + h[1] * y + h[2] * z + h[3] < 0.0) return false;
			return face.opening.rectangular || face.over(x, y, z);
		}
	}

	private final List<Passage> passages;
	private final IplRouteFrame anchorFrame;

	private IplRouteSolver(List<Passage> passages, IplRouteFrame anchorFrame) {
		this.passages = passages;
		this.anchorFrame = anchorFrame;
	}

	/** Splits the model hanging at {@code anchor} in {@code anchorFrame} by the openings near it. */
	static Result solve(List<IplModelRecorder.Recording> recordings, List<IplRouteOpening> openings,
						Vector3d anchor, IplRouteFrame anchorFrame) {
		List<Passage> passages = passages(IplRouteCut.of(openings), openings, anchor, anchorFrame);
		if (passages.isEmpty()) return new Result(null, anchorFrame);
		return new IplRouteSolver(passages, anchorFrame).split(recordings);
	}

	// ------------------------------------------------------------------ faces

	private static List<Passage> passages(List<IplRouteCut> cuts, List<IplRouteOpening> openings,
										  Vector3d anchor, IplRouteFrame anchorFrame) {
		List<Passage> own = new ArrayList<>(cuts.size());
		List<Passage> deeper = new ArrayList<>(0);
		for (IplRouteCut cut : cuts) {
			Passage best = null;
			int bestRank = Integer.MAX_VALUE;
			for (int member : cut.members()) {
				IplRouteOpening o = openings.get(Math.abs(member) - 1);
				IplRouteFrame outer = o.frame();
				IplRouteFrame inner = outer.through(o.opening());
				// Exit faces (leading back to the frame before) are the far side of the entry face,
				// which is in the same cut: the entry face alone decides, from its own front.
				if (inner == null || inner == outer || inner.parent != outer) continue;
				IplRouteRect face = o.rect();
				int rank;
				if (inner == anchorFrame) {
					rank = 0;
				} else if (outer == anchorFrame) {
					rank = face.side(anchor.x, anchor.y, anchor.z) >= 0.0 ? 1 : -1;
				} else {
					Vector3d entry = entryPoint(outer, anchor);
					rank = face.side(entry.x, entry.y, entry.z) >= 0.0 ? 2 : -1;
				}
				if (rank < 0 || rank >= bestRank) continue;
				bestRank = rank;
				best = new Passage(face, face.passageRegion(), outer, inner);
			}
			if (best != null) (bestRank <= 1 ? own : deeper).add(best);
		}
		// Deeper frames after the frames they are entered from.
		deeper.sort(Comparator.comparingInt(p -> p.outer().depth));
		own.addAll(deeper);
		return own;
	}

	/** Where {@code frame} is entered in route space: the centre of its entry, else the anchor. */
	private static Vector3d entryPoint(IplRouteFrame frame, Vector3d anchor) {
		if (frame.parent == null || frame.entry == null) return anchor;
		return frame.parent.rectOf(frame.entry).origin;
	}

	/** The frame of a point: each face in order moves it between its near and far frame. */
	private IplRouteFrame frameAt(double x, double y, double z) {
		IplRouteFrame frame = anchorFrame;
		for (Passage p : passages) {
			if (frame == p.outer || frame == p.inner) frame = p.contains(x, y, z) ? p.inner : p.outer;
		}
		return frame;
	}

	// ------------------------------------------------------------------ split

	private Result split(List<IplModelRecorder.Recording> recordings) {
		List<Part> parts = new ArrayList<>();
		IplRouteFrame common = null;
		boolean uniform = true;
		for (int r = 0; r < recordings.size(); r++) {
			IplModelRecorder.Recording recording = recordings.get(r);
			if (recording.vertices.isEmpty()) continue;
			if (recording.primitiveSize == 0) {
				// Strips and fans are not cut: the whole recording goes where its centre is.
				IplRouteFrame frame = frameOf(recording.vertices);
				parts.add(new Part(r, null, frame));
				if (common == null) common = frame;
				else if (common != frame) uniform = false;
				continue;
			}
			boolean polygon = recording.primitiveSize >= 3;
			List<List<IplVertex>> pieces = new ArrayList<>(4);
			for (int p = 0, n = recording.primitives(); p < n; p++) {
				pieces.clear();
				pieces.add(recording.primitive(p));
				for (Passage passage : passages) cut(pieces, passage.region, polygon);
				if (pieces.size() != 1) uniform = false;
				for (List<IplVertex> piece : pieces) {
					IplRouteFrame frame = frameOf(piece);
					parts.add(new Part(r, piece, frame));
					if (common == null) common = frame;
					else if (common != frame) uniform = false;
				}
			}
		}
		if (common == null) return new Result(null, anchorFrame);
		return uniform ? new Result(null, common) : new Result(parts, null);
	}

	private IplRouteFrame frameOf(List<IplVertex> vertices) {
		double x = 0.0, y = 0.0, z = 0.0;
		for (IplVertex v : vertices) {
			x += v.x;
			y += v.y;
			z += v.z;
		}
		double n = vertices.size();
		return frameAt(x / n, y / n, z / n);
	}

	/** Replaces every piece reaching across {@code region}'s boundary by its part inside and its parts outside. */
	private static void cut(List<List<IplVertex>> pieces, double[][] region, boolean polygon) {
		for (int i = pieces.size() - 1; i >= 0; i--) {
			List<IplVertex> piece = pieces.get(i);
			if (classify(piece, region) != ACROSS) continue;
			pieces.remove(i);
			List<IplVertex> rest = piece;
			int at = i;
			for (double[] h : region) {
				double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
				for (IplVertex v : rest) {
					double d = distance(v, h);
					if (d < min) min = d;
					if (d > max) max = d;
				}
				if (min >= -ON_PLANE) continue;
				if (max <= ON_PLANE) {
					pieces.add(at++, rest);
					rest = null;
					break;
				}
				List<IplVertex> outside = clip(rest, negate(h), polygon);
				if (valid(outside, polygon)) pieces.add(at++, outside);
				rest = clip(rest, h, polygon);
				if (!valid(rest, polygon)) {
					rest = null;
					break;
				}
			}
			if (rest != null) pieces.add(at, rest);
		}
	}

	/** Wholly inside the region, wholly outside one of its half-spaces, or across its boundary. */
	private static int classify(List<IplVertex> piece, double[][] region) {
		boolean across = false;
		for (double[] h : region) {
			double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
			for (IplVertex v : piece) {
				double d = distance(v, h);
				if (d < min) min = d;
				if (d > max) max = d;
			}
			if (max <= ON_PLANE) return OUTSIDE;
			if (min < -ON_PLANE) across = true;
		}
		return across ? ACROSS : INSIDE;
	}

	private static boolean valid(List<IplVertex> part, boolean polygon) {
		return part.size() >= (polygon ? 3 : 2);
	}

	/** The part of {@code in} where {@code plane} is non-negative. */
	private static List<IplVertex> clip(List<IplVertex> in, double[] plane, boolean polygon) {
		int n = in.size();
		List<IplVertex> out = new ArrayList<>(n + 2);
		int edges = polygon ? n : 1;
		for (int i = 0; i < edges; i++) {
			IplVertex p = in.get(i), q = in.get((i + 1) % n);
			double dp = distance(p, plane), dq = distance(q, plane);
			boolean pin = dp >= 0.0, qin = dq >= 0.0;
			if (pin) out.add(p);
			if (pin != qin) out.add(IplVertex.lerp(p, q, dp / (dp - dq)));
			if (!polygon && qin) out.add(q);
		}
		return out;
	}

	private static double distance(IplVertex v, double[] p) {
		return p[0] * v.x + p[1] * v.y + p[2] * v.z + p[3];
	}

	private static double[] negate(double[] plane) {
		return new double[]{-plane[0], -plane[1], -plane[2], -plane[3]};
	}
}
