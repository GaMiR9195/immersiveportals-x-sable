package ipl.sable.render.route;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which side of the portal openings each part of a model is on, by the path that leads to it
 * from the point the model hangs on: crossing a portal plane INSIDE an opening goes through,
 * crossing it anywhere else does not. There is no half-space test anywhere: a rope that sags
 * under the frame, a chain that wraps around a frame post, a spring beside the portal keep
 * every part where the path puts it, and only primitives that really pass an opening are cut,
 * at the opening.
 *
 * <p>Paths follow the model's own surface: the parts of one cut primitive are joined across
 * the cut, primitives sharing a written vertex are joined directly. Parts that the surface does
 * not join (separate chain links, the segments of a rope drawn as separate boxes) are joined to
 * the nearest already reached part by the straight gap between them, nearest first, starting
 * at the anchor. A state is a bit mask: bit {@code k} set means "went through opening k".
 */
final class IplLinkTopology {

	/** Vertices closer than this are one vertex (renderers recompute shared corners). */
	private static final double WELD = 1.0 / 1024.0;
	/** Gap joins are searched over at most this many vertices per joined part. */
	private static final int SAMPLES = 24;

	/** One convex part of a recorded primitive; {@code side}: openings it is beyond (only those that cut it). */
	record Piece(Object owner, int primitive, List<IplVertex> polygon, int side) {}

	private IplLinkTopology() {}

	// ------------------------------------------------------------------ cutting

	/** Cuts one primitive at every opening it passes through; appends its parts to {@code out}. */
	static void cut(Object owner, int primitive, List<IplVertex> vertices, boolean polygon,
					List<IplPortalRect> openings, List<Piece> out) {
		List<IplVertex> whole = vertices;
		List<List<IplVertex>> parts = new ArrayList<>(List.of(whole));
		List<Integer> sides = new ArrayList<>(List.of(0));
		for (int k = 0; k < openings.size(); k++) {
			IplPortalRect rect = openings.get(k);
			if (!passes(whole, polygon, rect)) continue;
			List<List<IplVertex>> nextParts = new ArrayList<>();
			List<Integer> nextSides = new ArrayList<>();
			for (int i = 0; i < parts.size(); i++) {
				List<IplVertex> entry = clip(parts.get(i), rect.entrySide(), polygon);
				List<IplVertex> passed = clip(parts.get(i), rect.passedSide(), polygon);
				if (valid(entry, polygon)) { nextParts.add(entry); nextSides.add(sides.get(i)); }
				if (valid(passed, polygon)) { nextParts.add(passed); nextSides.add(sides.get(i) | 1 << k); }
			}
			parts = nextParts;
			sides = nextSides;
		}
		for (int i = 0; i < parts.size(); i++) out.add(new Piece(owner, primitive, parts.get(i), sides.get(i)));
	}

	/** Whether an edge of the primitive passes the plane of {@code rect} inside the opening. */
	static boolean passes(List<IplVertex> v, boolean polygon, IplPortalRect rect) {
		int n = v.size();
		int edges = polygon ? n : 1;
		for (int i = 0; i < edges; i++) {
			IplVertex p = v.get(i), q = v.get((i + 1) % n);
			if (rect.passedBy(p.x, p.y, p.z, q.x, q.y, q.z)) return true;
		}
		return false;
	}

	private static boolean valid(List<IplVertex> part, boolean polygon) {
		return part.size() >= (polygon ? 3 : 2);
	}

	/** The part of {@code in} on the non-negative side of {@code plane}. */
	private static List<IplVertex> clip(List<IplVertex> in, double[] plane, boolean polygon) {
		int n = in.size();
		List<IplVertex> out = new ArrayList<>(n + 2);
		if (!valid(in, polygon)) return out;
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

	// ------------------------------------------------------------------ classification

	/** Openings passed on the straight way from {@code (px, py, pz)} to {@code q}. */
	private static int passedOn(double px, double py, double pz, IplVertex q, List<IplPortalRect> openings) {
		int mask = 0;
		for (int k = 0; k < openings.size(); k++) {
			if (openings.get(k).passedBy(px, py, pz, q.x, q.y, q.z)) mask |= 1 << k;
		}
		return mask;
	}

	/**
	 * The state of every piece, for a model hanging at {@code anchor} whose state there is
	 * {@code anchorState}.
	 */
	static int[] classify(List<Piece> pieces, List<IplPortalRect> openings, Vector3d anchor, int anchorState) {
		int n = pieces.size();
		int[] states = new int[n];
		if (n == 0) return states;
		ParityForest forest = new ParityForest(n);

		// Parts of one primitive: through each other across the cut.
		for (int i = 1; i < n; i++) {
			Piece a = pieces.get(i - 1), b = pieces.get(i);
			if (a.owner() == b.owner() && a.primitive() == b.primitive()) forest.join(i - 1, i, a.side() ^ b.side());
		}
		// Primitives sharing a written vertex: the same state.
		Map<VertexKey, Integer> firstAt = new HashMap<>();
		for (int i = 0; i < n; i++) {
			for (IplVertex v : pieces.get(i).polygon()) {
				if (!v.original) continue;
				Integer other = firstAt.putIfAbsent(VertexKey.of(v), i);
				if (other != null) forest.join(other, i, 0);
			}
		}

		// Joined parts: their written vertices (sampled) for the gap joins.
		Map<Integer, List<int[]>> samples = new HashMap<>();
		for (int i = 0; i < n; i++) {
			List<IplVertex> polygon = pieces.get(i).polygon();
			for (int j = 0; j < polygon.size(); j++) {
				if (polygon.get(j).original) samples.computeIfAbsent(forest.root(i), r -> new ArrayList<>()).add(new int[]{i, j});
			}
		}
		if (samples.isEmpty()) return states;
		for (List<int[]> list : samples.values()) thin(list);

		// Start at the written vertex nearest to the anchor.
		int[] start = null;
		double best = Double.POSITIVE_INFINITY;
		for (List<int[]> list : samples.values()) {
			for (int[] s : list) {
				double d = vertex(pieces, s).distanceSquared(anchor.x, anchor.y, anchor.z);
				if (d < best) { best = d; start = s; }
			}
		}
		Map<Integer, Integer> rootStates = new HashMap<>();
		int startState = anchorState ^ passedOn(anchor.x, anchor.y, anchor.z, vertex(pieces, start), openings);
		rootStates.put(forest.root(start[0]), startState ^ forest.parity(start[0]));

		// Nearest-first gap joins of the remaining parts (Prim over the sampled vertices).
		List<int[]> candidates = new ArrayList<>();
		for (Map.Entry<Integer, List<int[]>> e : samples.entrySet()) {
			if (e.getKey() != forest.root(start[0])) candidates.addAll(e.getValue());
		}
		double[] gap = new double[candidates.size()];
		int[][] nearest = new int[candidates.size()][];
		java.util.Arrays.fill(gap, Double.POSITIVE_INFINITY);
		List<int[]> joined = samples.get(forest.root(start[0]));
		while (true) {
			int pick = -1;
			best = Double.POSITIVE_INFINITY;
			for (int c = 0; c < candidates.size(); c++) {
				int[] u = candidates.get(c);
				if (rootStates.containsKey(forest.root(u[0]))) continue;
				IplVertex uv = vertex(pieces, u);
				for (int[] r : joined) {
					double d = vertex(pieces, r).distanceSquared(uv.x, uv.y, uv.z);
					if (d < gap[c]) { gap[c] = d; nearest[c] = r; }
				}
				if (gap[c] < best) { best = gap[c]; pick = c; }
			}
			if (pick < 0) break;
			int[] from = nearest[pick], to = candidates.get(pick);
			IplVertex fv = vertex(pieces, from);
			int fromState = rootStates.get(forest.root(from[0])) ^ forest.parity(from[0]);
			int toState = fromState ^ passedOn(fv.x, fv.y, fv.z, vertex(pieces, to), openings);
			int root = forest.root(to[0]);
			rootStates.put(root, toState ^ forest.parity(to[0]));
			joined = samples.get(root);
		}

		for (int i = 0; i < n; i++) {
			Integer root = rootStates.get(forest.root(i));
			states[i] = (root == null ? anchorState : root) ^ forest.parity(i);
		}
		return states;
	}

	private static IplVertex vertex(List<Piece> pieces, int[] at) {
		return pieces.get(at[0]).polygon().get(at[1]);
	}

	/** Keeps at most {@link #SAMPLES} evenly spread entries. */
	private static void thin(List<int[]> list) {
		if (list.size() <= SAMPLES) return;
		List<int[]> kept = new ArrayList<>(SAMPLES);
		double step = (double) list.size() / SAMPLES;
		for (int i = 0; i < SAMPLES; i++) kept.add(list.get((int) (i * step)));
		list.clear();
		list.addAll(kept);
	}

	private record VertexKey(long x, long y, long z) {
		static VertexKey of(IplVertex v) {
			return new VertexKey(Math.round(v.x / WELD), Math.round(v.y / WELD), Math.round(v.z / WELD));
		}
	}

	/** Union-find whose links carry the state difference between the joined pieces. */
	private static final class ParityForest {
		private final int[] parent;
		private final int[] toParent;

		ParityForest(int n) {
			parent = new int[n];
			toParent = new int[n];
			for (int i = 0; i < n; i++) parent[i] = i;
		}

		int root(int i) {
			while (parent[i] != i) {
				int p = parent[i];
				if (parent[p] != p) {
					toParent[i] ^= toParent[p];
					parent[i] = parent[p];
				}
				i = parent[i];
			}
			return i;
		}

		/** State of {@code i} relative to its root. */
		int parity(int i) {
			int mask = 0;
			while (parent[i] != i) {
				mask ^= toParent[i];
				i = parent[i];
			}
			return mask;
		}

		/** {@code state(a) ^ state(b) == difference}; a contradicting join (an odd loop around a frame) is ignored. */
		void join(int a, int b, int difference) {
			int ra = root(a), rb = root(b);
			if (ra == rb) return;
			parent[rb] = ra;
			toParent[rb] = parity(a) ^ parity(b) ^ difference;
		}
	}
}
