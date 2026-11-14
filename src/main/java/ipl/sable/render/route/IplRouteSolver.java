package ipl.sable.render.route;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which frame each part of a recorded model is drawn in, by the path that leads to it from the
 * point the model hangs on. There is no half-space test: a path passes an opening only where it
 * crosses the portal plane INSIDE the finite opening, so a rope sagging under a frame, a chain
 * wrapping a post or a spring beside a portal stay where they are, and a primitive that really
 * passes an opening is cut there, at the opening.
 *
 * <p>Cutting: a primitive that passes an opening is split into its FRONT part, the part behind
 * the opening (THROUGH) and the parts behind the plane beside the opening (BESIDE). Parts of
 * one primitive are joined across the cut: FRONT–THROUGH passes the opening, FRONT–BESIDE goes
 * around the rim, THROUGH and BESIDE never touch (a solid cannot pass a frame post).
 *
 * <p>Paths follow the model's surface: parts of one primitive across their cut, primitives that
 * share a written vertex directly (passing the opening when that vertex lies on its plane).
 * Pieces the surface does not join (separate chain links, rope segments drawn as separate boxes)
 * are joined to the nearest reached piece by the straight gap between them, nearest first,
 * starting at the anchor. Every step moves through the frame tree ({@link IplRouteFrame#cross}),
 * so nested portals and exit faces need no special case.
 */
final class IplRouteSolver {

	static final byte NONE = -1;
	static final byte FRONT = 0;
	static final byte THROUGH = 1;
	static final byte BESIDE = 2;

	/** Vertices closer than this are one vertex (renderers recompute shared corners). */
	private static final double WELD = 1.0 / 1024.0;
	/** A vertex this close to a cut plane belongs to both sides and joins nothing by itself. */
	private static final double ON_PLANE = 1.0e-6;
	/** A welded vertex this close to an opening's plane may pass it. */
	private static final double WELD_ON_PLANE = 1.0e-4;
	/** Gap joins are searched over at most this many vertices per joined piece. */
	private static final int SAMPLES = 24;
	private static final int[] NO_STEPS = new int[0];

	/** One convex part of a recorded primitive and the frame it is drawn in. */
	static final class Part {
		final int recording;
		final int primitive;
		final List<IplVertex> polygon;
		/** Zone per opening index for the openings that cut the primitive ({@link #NONE} elsewhere), or null. */
		final byte @Nullable [] zones;
		@Nullable IplRouteFrame frame;

		Part(int recording, int primitive, List<IplVertex> polygon, byte @Nullable [] zones) {
			this.recording = recording;
			this.primitive = primitive;
			this.polygon = polygon;
			this.zones = zones;
		}
	}

	/** A join from one part to another and the openings it passes on the way (+k forward, -k backward, 1-based). */
	private record Link(int to, int[] steps) {}

	private final List<IplRouteOpening> openings;
	private final List<Part> parts = new ArrayList<>();
	private final List<List<Link>> links = new ArrayList<>();

	private IplRouteSolver(List<IplRouteOpening> openings) {
		this.openings = openings;
	}

	/**
	 * Cuts the primitives of {@code recordings} at {@code openings} and gives every part its
	 * frame, for a model hanging at {@code anchor} in {@code anchorFrame}. Parts are returned in
	 * recording and primitive order.
	 */
	static List<Part> solve(List<IplModelRecorder.Recording> recordings, List<IplRouteOpening> openings,
							Vector3d anchor, IplRouteFrame anchorFrame) {
		IplRouteSolver solver = new IplRouteSolver(openings);
		for (int r = 0; r < recordings.size(); r++) solver.cutRecording(r, recordings.get(r));
		solver.joinParts();
		solver.assignFrames(anchor, anchorFrame);
		return solver.parts;
	}

	// ------------------------------------------------------------------ cutting

	private void cutRecording(int index, IplModelRecorder.Recording recording) {
		boolean polygon = recording.primitiveSize >= 3;
		for (int p = 0, n = recording.primitives(); p < n; p++) {
			List<IplVertex> vertices = recording.primitive(p);
			List<Part> current = List.of(new Part(index, p, vertices, null));
			for (int k = 0; k < openings.size(); k++) {
				IplRouteRect rect = openings.get(k).rect();
				if (!passes(vertices, polygon, rect)) continue;
				List<Part> next = new ArrayList<>(current.size() * 2 + 1);
				for (Part part : current) split(part, k, rect, polygon, next);
				current = next;
			}
			parts.addAll(current);
		}
	}

	/** Whether the primitive passes the plane of {@code rect} inside the opening. */
	private static boolean passes(List<IplVertex> v, boolean polygon, IplRouteRect rect) {
		if (!polygon) {
			IplVertex p = v.get(0), q = v.get(1);
			return !Double.isNaN(rect.passage(p.x, p.y, p.z, q.x, q.y, q.z));
		}
		Vector3d a = null, b = null;
		for (int i = 0, n = v.size(); i < n; i++) {
			IplVertex p = v.get(i), q = v.get((i + 1) % n);
			double dp = rect.side(p), dq = rect.side(q);
			if ((dp >= 0.0) == (dq >= 0.0)) continue;
			double t = dp / (dp - dq);
			Vector3d c = new Vector3d(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, p.z + (q.z - p.z) * t);
			if (a == null) a = c;
			else b = c;
		}
		return a != null && b != null && rect.chordOverlaps(a, b);
	}

	private void split(Part part, int k, IplRouteRect rect, boolean polygon, List<Part> out) {
		add(out, part, k, clip(part.polygon, rect.frontSpace(), polygon), FRONT, polygon);
		List<IplVertex> rest = clip(part.polygon, rect.backSpace(), polygon);
		for (double[] beside : rect.besideSpaces()) {
			if (!valid(rest, polygon)) return;
			add(out, part, k, clip(rest, beside, polygon), BESIDE, polygon);
			rest = clip(rest, negate(beside), polygon);
		}
		add(out, part, k, rest, THROUGH, polygon);
	}

	private void add(List<Part> out, Part from, int k, List<IplVertex> polygon, byte zone, boolean isPolygon) {
		if (!valid(polygon, isPolygon)) return;
		byte[] zones = from.zones == null ? filledZones() : from.zones.clone();
		zones[k] = zone;
		out.add(new Part(from.recording, from.primitive, polygon, zones));
	}

	private byte[] filledZones() {
		byte[] zones = new byte[openings.size()];
		Arrays.fill(zones, NONE);
		return zones;
	}

	private static boolean valid(List<IplVertex> part, boolean polygon) {
		return part.size() >= (polygon ? 3 : 2);
	}

	/** The part of {@code in} where {@code plane} is non-negative; vertices on the plane are detached. */
	private static List<IplVertex> clip(List<IplVertex> in, double[] plane, boolean polygon) {
		int n = in.size();
		List<IplVertex> out = new ArrayList<>(n + 2);
		if (!valid(in, polygon)) return out;
		int edges = polygon ? n : 1;
		for (int i = 0; i < edges; i++) {
			IplVertex p = in.get(i), q = in.get((i + 1) % n);
			double dp = distance(p, plane), dq = distance(q, plane);
			boolean pin = dp >= 0.0, qin = dq >= 0.0;
			if (pin) out.add(Math.abs(dp) < ON_PLANE ? p.detached() : p);
			if (pin != qin) out.add(IplVertex.lerp(p, q, dp / (dp - dq)));
			if (!polygon && qin) out.add(Math.abs(dq) < ON_PLANE ? q.detached() : q);
		}
		return out;
	}

	private static double distance(IplVertex v, double[] p) {
		return p[0] * v.x + p[1] * v.y + p[2] * v.z + p[3];
	}

	private static double[] negate(double[] plane) {
		return new double[]{-plane[0], -plane[1], -plane[2], -plane[3]};
	}

	// ------------------------------------------------------------------ joins

	private void joinParts() {
		for (int i = 0; i < parts.size(); i++) links.add(new ArrayList<>(2));
		joinAcrossCuts();
		joinAtSharedVertices();
	}

	/** Parts of one primitive, through each other across the cut. */
	private void joinAcrossCuts() {
		int start = 0;
		for (int i = 1; i <= parts.size(); i++) {
			if (i < parts.size() && samePrimitive(parts.get(i), parts.get(start))) continue;
			for (int a = start; a < i; a++) {
				for (int b = a + 1; b < i; b++) {
					int[] steps = cutSteps(parts.get(a).zones, parts.get(b).zones);
					if (steps != null) link(a, b, steps);
				}
			}
			start = i;
		}
	}

	private static boolean samePrimitive(Part a, Part b) {
		return a.recording == b.recording && a.primitive == b.primitive;
	}

	/** Openings passed from a part to a sibling part of one primitive; null when they do not touch. */
	private static int @Nullable [] cutSteps(byte @Nullable [] from, byte @Nullable [] to) {
		if (from == null || to == null) return NO_STEPS;
		int[] steps = new int[from.length];
		int n = 0;
		for (int k = 0; k < from.length; k++) {
			byte a = from[k], b = to[k];
			if (a == b) continue;
			if (a != FRONT && b != FRONT) return null;
			if (a == THROUGH) steps[n++] = -(k + 1);
			else if (b == THROUGH) steps[n++] = k + 1;
		}
		return n == 0 ? NO_STEPS : Arrays.copyOf(steps, n);
	}

	/** Primitives sharing a written vertex; they pass an opening there only when the vertex lies on its plane. */
	private void joinAtSharedVertices() {
		Map<VertexKey, Integer> firstAt = new HashMap<>();
		for (int i = 0; i < parts.size(); i++) {
			for (IplVertex v : parts.get(i).polygon) {
				if (!v.original) continue;
				Integer other = firstAt.putIfAbsent(VertexKey.of(v), i);
				if (other != null && other != i) link(other, i, weldSteps(parts.get(other), parts.get(i), v));
			}
		}
	}

	private int[] weldSteps(Part from, Part to, IplVertex at) {
		int[] steps = null;
		int n = 0;
		for (int k = 0; k < openings.size(); k++) {
			IplRouteRect rect = openings.get(k).rect();
			if (Math.abs(rect.side(at)) > WELD_ON_PLANE || !rect.containsOnPlane(at.x, at.y, at.z)) continue;
			double a = centroidSide(from, rect), b = centroidSide(to, rect);
			if ((a >= 0.0) == (b >= 0.0)) continue;
			if (steps == null) steps = new int[openings.size()];
			steps[n++] = a >= 0.0 ? k + 1 : -(k + 1);
		}
		return steps == null ? NO_STEPS : Arrays.copyOf(steps, n);
	}

	private static double centroidSide(Part part, IplRouteRect rect) {
		double sum = 0.0;
		for (IplVertex v : part.polygon) sum += rect.side(v);
		return sum / part.polygon.size();
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

	private void assignFrames(Vector3d anchor, IplRouteFrame anchorFrame) {
		if (parts.isEmpty()) return;
		int[] component = components();
		Map<Integer, List<int[]>> samples = samples(component);
		if (samples.isEmpty()) {
			for (Part p : parts) p.frame = anchorFrame;
			return;
		}

		// Start at the written vertex nearest to the anchor.
		int[] start = null;
		double best = Double.POSITIVE_INFINITY;
		for (List<int[]> list : samples.values()) {
			for (int[] s : list) {
				double d = vertex(s).distanceSquared(anchor.x, anchor.y, anchor.z);
				if (d < best) { best = d; start = s; }
			}
		}
		IplVertex first = vertex(start);
		flood(start[0], walk(anchorFrame, anchor.x, anchor.y, anchor.z, first));

		// Nearest-first gap joins of the remaining pieces (Prim over the sampled vertices).
		List<int[]> candidates = new ArrayList<>();
		for (Map.Entry<Integer, List<int[]>> e : samples.entrySet()) {
			if (e.getKey() != component[start[0]]) candidates.addAll(e.getValue());
		}
		double[] gap = new double[candidates.size()];
		int[][] nearest = new int[candidates.size()][];
		Arrays.fill(gap, Double.POSITIVE_INFINITY);
		List<int[]> joined = samples.get(component[start[0]]);
		while (true) {
			int pick = -1;
			best = Double.POSITIVE_INFINITY;
			for (int c = 0; c < candidates.size(); c++) {
				int[] u = candidates.get(c);
				if (parts.get(u[0]).frame != null) continue;
				IplVertex uv = vertex(u);
				for (int[] r : joined) {
					double d = vertex(r).distanceSquared(uv.x, uv.y, uv.z);
					if (d < gap[c]) { gap[c] = d; nearest[c] = r; }
				}
				if (gap[c] < best) { best = gap[c]; pick = c; }
			}
			if (pick < 0) break;
			int[] from = nearest[pick], to = candidates.get(pick);
			IplVertex fv = vertex(from);
			flood(to[0], walk(parts.get(from[0]).frame, fv.x, fv.y, fv.z, vertex(to)));
			joined = samples.get(component[to[0]]);
		}
		for (Part p : parts) if (p.frame == null) p.frame = anchorFrame;
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
				Part next = parts.get(link.to());
				if (next.frame != null) continue;
				next.frame = apply(here, link.steps());
				queue.add(link.to());
			}
		}
	}

	/**
	 * Applies the crossings of one move in order. Coplanar openings (both faces of a two-sided
	 * portal, an exit face over its entry) are one physical crossing: once one of them moved the
	 * frame, the others on that plane are skipped.
	 */
	private IplRouteFrame apply(IplRouteFrame frame, int[] steps) {
		List<IplRouteRect> crossed = null;
		for (int step : steps) {
			IplRouteOpening o = openings.get(Math.abs(step) - 1);
			if (crossed != null && crossesAgain(crossed, o.rect())) continue;
			IplRouteFrame next = frame.cross(o, step > 0);
			if (next == frame) continue;
			frame = next;
			if (crossed == null) crossed = new ArrayList<>(2);
			crossed.add(o.rect());
		}
		return frame;
	}

	private static boolean crossesAgain(List<IplRouteRect> crossed, IplRouteRect rect) {
		for (IplRouteRect c : crossed) if (c.coplanar(rect)) return true;
		return false;
	}

	/** The frame reached on the straight way from {@code (px, py, pz)} in {@code frame} to {@code q}. */
	private IplRouteFrame walk(IplRouteFrame frame, double px, double py, double pz, IplVertex q) {
		int n = openings.size();
		double[] at = new double[n];
		Integer[] order = new Integer[n];
		int count = 0;
		for (int k = 0; k < n; k++) {
			double t = openings.get(k).rect().passage(px, py, pz, q.x, q.y, q.z);
			if (Double.isNaN(t)) continue;
			at[k] = t;
			order[count++] = k;
		}
		if (count == 0) return frame;
		Arrays.sort(order, 0, count, (a, b) -> Double.compare(at[a], at[b]));
		int[] steps = new int[count];
		for (int i = 0; i < count; i++) {
			int k = order[i];
			steps[i] = openings.get(k).rect().side(px, py, pz) >= 0.0 ? k + 1 : -(k + 1);
		}
		return apply(frame, steps);
	}

	/** Connected pieces over all links (union-find), as the root index of each part. */
	private int[] components() {
		int[] parent = new int[parts.size()];
		for (int i = 0; i < parent.length; i++) parent[i] = i;
		for (int a = 0; a < parent.length; a++) {
			for (Link link : links.get(a)) {
				int ra = find(parent, a), rb = find(parent, link.to());
				if (ra != rb) parent[rb] = ra;
			}
		}
		for (int i = 0; i < parent.length; i++) parent[i] = find(parent, i);
		return parent;
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	/** Written vertices of each piece, at most {@link #SAMPLES} spread evenly, as {part, vertex}. */
	private Map<Integer, List<int[]>> samples(int[] component) {
		Map<Integer, List<int[]>> samples = new HashMap<>();
		for (int i = 0; i < parts.size(); i++) {
			List<IplVertex> polygon = parts.get(i).polygon;
			for (int j = 0; j < polygon.size(); j++) {
				if (polygon.get(j).original) samples.computeIfAbsent(component[i], r -> new ArrayList<>()).add(new int[]{i, j});
			}
		}
		for (List<int[]> list : samples.values()) thin(list);
		return samples;
	}

	private static void thin(List<int[]> list) {
		if (list.size() <= SAMPLES) return;
		List<int[]> kept = new ArrayList<>(SAMPLES);
		double step = (double) list.size() / SAMPLES;
		for (int i = 0; i < SAMPLES; i++) kept.add(list.get((int) (i * step)));
		list.clear();
		list.addAll(kept);
	}

	private IplVertex vertex(int[] at) {
		return parts.get(at[0]).polygon.get(at[1]);
	}

	private record VertexKey(long x, long y, long z) {
		static VertexKey of(IplVertex v) {
			return new VertexKey(Math.round(v.x / WELD), Math.round(v.y / WELD), Math.round(v.z / WELD));
		}
	}
}
