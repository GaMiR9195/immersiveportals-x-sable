package ipl.sable.render.route;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which frame each part of a recorded model is drawn in, by the path that leads to it from the
 * point the model hangs on. There is no half-space test: a path passes an opening only where it
 * crosses the portal plane INSIDE the finite opening, so a rope sagging under a frame, a chain
 * wrapping a post or a spring beside a portal stay where they are, and a primitive that really
 * passes an opening is cut there, at the opening ({@link IplPrimitiveCutter}), once per physical
 * plane ({@link IplRouteCut}).
 *
 * <p>Paths follow the model's surface: parts of one primitive across their cut, primitives that
 * share a written vertex directly (passing the opening when that vertex lies on its plane).
 * Pieces the surface does not join (separate chain links, rope segments drawn as separate boxes)
 * are joined nearest-first by the straight gaps between them ({@link IplGapForest}), starting at
 * the anchor. Every step moves through the frame tree ({@link IplRouteFrame#cross}), so nested
 * portals and exit faces need no special case.
 */
final class IplRouteSolver {

	/** Vertices closer than this are one vertex (renderers recompute shared corners). */
	private static final double WELD = 1.0 / 256.0;
	/** Weld keys cover this many weld steps each side of the anchor (21 bits per axis). */
	private static final long WELD_RANGE = 1L << 20;
	/** A welded vertex this close to an opening's plane may pass it. */
	private static final double WELD_ON_PLANE = 1.0e-4;
	/** Gap joins are searched over at most this many vertices per joined piece. */
	private static final int SAMPLES = 16;
	private static final int[] NO_STEPS = new int[0];

	/** A join from one part to another and the cuts it passes on the way (+k front to back, -k back to front, 1-based). */
	private record Link(int to, int[] steps) {}

	private final List<IplRouteOpening> openings;
	private final List<IplRouteCut> cuts;
	private final Vector3d anchor;
	private final List<IplPrimitiveCutter.Part> parts;
	private final List<List<Link>> links;

	private IplRouteSolver(List<IplRouteOpening> openings, List<IplRouteCut> cuts, Vector3d anchor,
						   List<IplPrimitiveCutter.Part> parts) {
		this.openings = openings;
		this.cuts = cuts;
		this.anchor = anchor;
		this.parts = parts;
		this.links = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) links.add(new ArrayList<>(2));
	}

	/**
	 * Cuts the primitives of {@code recordings} at {@code openings} and gives every part its
	 * frame, for a model hanging at {@code anchor} in {@code anchorFrame}. Parts are returned in
	 * recording and primitive order.
	 */
	static List<IplPrimitiveCutter.Part> solve(List<IplModelRecorder.Recording> recordings, List<IplRouteOpening> openings,
											   Vector3d anchor, IplRouteFrame anchorFrame) {
		List<IplRouteCut> cuts = IplRouteCut.of(openings);
		List<IplPrimitiveCutter.Part> parts = IplPrimitiveCutter.cut(recordings, cuts);
		IplRouteSolver solver = new IplRouteSolver(openings, cuts, anchor, parts);
		solver.joinAcrossCuts();
		solver.joinAtSharedVertices();
		solver.assignFrames(anchorFrame);
		return parts;
	}

	// ------------------------------------------------------------------ joins

	/** Parts of one primitive, through each other across the cut. */
	private void joinAcrossCuts() {
		int start = 0;
		for (int i = 1; i <= parts.size(); i++) {
			if (i < parts.size() && parts.get(i).samePrimitive(parts.get(start))) continue;
			for (int a = start; a < i; a++) {
				for (int b = a + 1; b < i; b++) {
					int[] steps = IplPrimitiveCutter.steps(parts.get(a).zones, parts.get(b).zones);
					if (steps != null) link(a, b, steps);
				}
			}
			start = i;
		}
	}

	/** Primitives sharing a written vertex; they pass an opening there only when the vertex lies on its plane. */
	private void joinAtSharedVertices() {
		Long2IntOpenHashMap firstAt = new Long2IntOpenHashMap(parts.size() * 4);
		firstAt.defaultReturnValue(-1);
		for (int i = 0; i < parts.size(); i++) {
			for (IplVertex v : parts.get(i).polygon) {
				if (!v.original) continue;
				long key = weldKey(v);
				if (key == Long.MIN_VALUE) continue;
				int other = firstAt.putIfAbsent(key, i);
				if (other >= 0 && other != i) link(other, i, weldSteps(parts.get(other), parts.get(i), v));
			}
		}
	}

	/** Quantized position relative to the anchor packed in 63 bits; MIN_VALUE when out of range. */
	private long weldKey(IplVertex v) {
		long qx = Math.round((v.x - anchor.x) / WELD), qy = Math.round((v.y - anchor.y) / WELD), qz = Math.round((v.z - anchor.z) / WELD);
		if (Math.abs(qx) >= WELD_RANGE || Math.abs(qy) >= WELD_RANGE || Math.abs(qz) >= WELD_RANGE) return Long.MIN_VALUE;
		return ((qx + WELD_RANGE) << 42) | ((qy + WELD_RANGE) << 21) | (qz + WELD_RANGE);
	}

	private int[] weldSteps(IplPrimitiveCutter.Part from, IplPrimitiveCutter.Part to, IplVertex at) {
		int[] steps = null;
		int n = 0;
		for (int k = 0; k < cuts.size(); k++) {
			IplRouteRect rect = cuts.get(k).rect();
			if (Math.abs(rect.side(at)) > WELD_ON_PLANE || !rect.containsOnPlane(at.x, at.y, at.z)) continue;
			double a = from.centroidSide(rect), b = to.centroidSide(rect);
			if ((a >= 0.0) == (b >= 0.0)) continue;
			if (steps == null) steps = new int[cuts.size()];
			steps[n++] = a >= 0.0 ? k + 1 : -(k + 1);
		}
		return steps == null ? NO_STEPS : Arrays.copyOf(steps, n);
	}

	private void link(int a, int b, int[] steps) {
		links.get(a).add(new Link(b, steps));
		links.get(b).add(new Link(a, reversed(steps)));
	}

	private static int[] reversed(int[] steps) {
		if (steps.length == 0) return steps;
		int[] out = new int[steps.length];
		for (int i = 0; i < steps.length; i++) out[i] = -steps[steps.length - 1 - i];
		return out;
	}

	// ------------------------------------------------------------------ frames

	private void assignFrames(IplRouteFrame anchorFrame) {
		if (parts.isEmpty()) return;
		int[] piece = new int[parts.size()];
		int pieces = pieces(piece);
		IntArrayList samplePart = new IntArrayList(), sampleVertex = new IntArrayList();
		sample(piece, pieces, samplePart, sampleVertex);
		int n = samplePart.size();
		if (n == 0) {
			for (IplPrimitiveCutter.Part p : parts) p.frame = anchorFrame;
			return;
		}
		double[] x = new double[n], y = new double[n], z = new double[n];
		int[] samplePiece = new int[n];
		int start = 0;
		double best = Double.POSITIVE_INFINITY;
		for (int s = 0; s < n; s++) {
			IplVertex v = vertex(samplePart.getInt(s), sampleVertex.getInt(s));
			x[s] = v.x; y[s] = v.y; z[s] = v.z;
			samplePiece[s] = piece[samplePart.getInt(s)];
			double d = v.distanceSquared(anchor.x, anchor.y, anchor.z);
			if (d < best) { best = d; start = s; }
		}
		IplVertex first = vertex(samplePart.getInt(start), sampleVertex.getInt(start));
		flood(samplePart.getInt(start), walk(anchorFrame, anchor.x, anchor.y, anchor.z, first));
		for (IplGapForest.Join join : IplGapForest.joins(x, y, z, samplePiece, pieces, start)) {
			IplRouteFrame from = parts.get(samplePart.getInt(join.from())).frame;
			IplVertex to = vertex(samplePart.getInt(join.to()), sampleVertex.getInt(join.to()));
			flood(samplePart.getInt(join.to()), walk(from, x[join.from()], y[join.from()], z[join.from()], to));
		}
		for (IplPrimitiveCutter.Part p : parts) if (p.frame == null) p.frame = anchorFrame;
	}

	/** Connected pieces over all links: the piece index ({@code 0..}) of every part; returns the count. */
	private int pieces(int[] piece) {
		int[] parent = new int[parts.size()];
		for (int i = 0; i < parent.length; i++) parent[i] = i;
		for (int a = 0; a < parent.length; a++) {
			for (Link link : links.get(a)) {
				int ra = find(parent, a), rb = find(parent, link.to());
				if (ra != rb) parent[rb] = ra;
			}
		}
		int[] index = new int[parent.length];
		Arrays.fill(index, -1);
		int count = 0;
		for (int i = 0; i < parent.length; i++) {
			int root = find(parent, i);
			if (index[root] < 0) index[root] = count++;
			piece[i] = index[root];
		}
		return count;
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	/** Written vertices of each piece, at most {@link #SAMPLES} spread evenly. */
	private void sample(int[] piece, int pieces, IntArrayList samplePart, IntArrayList sampleVertex) {
		int[] total = new int[pieces];
		for (int i = 0; i < parts.size(); i++) {
			for (IplVertex v : parts.get(i).polygon) if (v.original) total[piece[i]]++;
		}
		int[] seen = new int[pieces];
		int[] taken = new int[pieces];
		for (int i = 0; i < parts.size(); i++) {
			List<IplVertex> polygon = parts.get(i).polygon;
			int p = piece[i];
			for (int j = 0; j < polygon.size(); j++) {
				if (!polygon.get(j).original) continue;
				int k = seen[p]++;
				// Keep the k-th vertex when it starts a new share of SAMPLES (even spread).
				if ((long) taken[p] * total[p] > (long) k * SAMPLES) continue;
				taken[p]++;
				samplePart.add(i);
				sampleVertex.add(j);
			}
		}
	}

	/** Gives {@code start} its frame and every part reachable over links the frame its steps lead to. */
	private void flood(int start, IplRouteFrame frame) {
		if (parts.get(start).frame != null) return;
		parts.get(start).frame = frame;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			int at = queue.poll();
			IplRouteFrame here = parts.get(at).frame;
			for (Link link : links.get(at)) {
				IplPrimitiveCutter.Part next = parts.get(link.to());
				if (next.frame != null) continue;
				next.frame = apply(here, link.steps());
				queue.add(link.to());
			}
		}
	}

	/** Applies the cut crossings of one move in order. */
	private IplRouteFrame apply(IplRouteFrame frame, int[] steps) {
		for (int step : steps) frame = cuts.get(Math.abs(step) - 1).crossedFrom(frame, step > 0, openings);
		return frame;
	}

	/** The frame reached on the straight way from {@code (px, py, pz)} in {@code frame} to {@code q}. */
	private IplRouteFrame walk(IplRouteFrame frame, double px, double py, double pz, IplVertex q) {
		int n = cuts.size();
		double[] at = null;
		int[] hits = null;
		int count = 0;
		for (int k = 0; k < n; k++) {
			double t = cuts.get(k).rect().passage(px, py, pz, q.x, q.y, q.z);
			if (Double.isNaN(t)) continue;
			if (at == null) { at = new double[n]; hits = new int[n]; }
			// Insertion by distance along the way.
			int i = count++;
			while (i > 0 && at[i - 1] > t) { at[i] = at[i - 1]; hits[i] = hits[i - 1]; i--; }
			at[i] = t;
			hits[i] = k;
		}
		if (count == 0) return frame;
		int[] steps = new int[count];
		for (int i = 0; i < count; i++) {
			int k = hits[i];
			steps[i] = cuts.get(k).rect().side(px, py, pz) >= 0.0 ? k + 1 : -(k + 1);
		}
		return apply(frame, steps);
	}

	private IplVertex vertex(int part, int index) {
		return parts.get(part).polygon.get(index);
	}
}
