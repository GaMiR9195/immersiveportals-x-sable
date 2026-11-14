package ipl.sable.render.route;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cuts recorded primitives where they pass openings, once per physical plane ({@link IplRouteCut}).
 * A primitive that passes a cut (its plane section overlaps the finite opening) is split into its
 * FRONT part, the part behind the opening (THROUGH) and — only when it reaches past the rim — the
 * parts behind the plane beside the opening (BESIDE). Parts of one primitive are joined across
 * the cut: FRONT–THROUGH passes the opening, FRONT–BESIDE goes around the rim, THROUGH and
 * BESIDE never touch (a solid cannot pass a frame post).
 */
final class IplPrimitiveCutter {

	static final byte NONE = -1;
	static final byte FRONT = 0;
	static final byte THROUGH = 1;
	static final byte BESIDE = 2;

	/** A vertex this close to a cut plane belongs to both sides and joins nothing by itself. */
	private static final double ON_PLANE = 1.0e-6;
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

		boolean samePrimitive(Part other) {
			return recording == other.recording && primitive == other.primitive;
		}

		double centroidSide(IplRouteRect rect) {
			double sum = 0.0;
			for (IplVertex v : polygon) sum += rect.side(v);
			return sum / polygon.size();
		}
	}

	private IplPrimitiveCutter() {}

	/** Every complete primitive of {@code recordings}, cut at the planes it passes, in order. */
	static List<Part> cut(List<IplModelRecorder.Recording> recordings, List<IplRouteCut> cuts) {
		List<Part> out = new ArrayList<>();
		for (int r = 0; r < recordings.size(); r++) {
			IplModelRecorder.Recording recording = recordings.get(r);
			boolean polygon = recording.primitiveSize >= 3;
			for (int p = 0, n = recording.primitives(); p < n; p++) {
				List<IplVertex> vertices = recording.primitive(p);
				List<Part> current = null;
				for (int k = 0; k < cuts.size(); k++) {
					IplRouteRect rect = cuts.get(k).rect();
					if (!passes(vertices, polygon, rect)) continue;
					if (current == null) current = List.of(new Part(r, p, vertices, null));
					List<Part> next = new ArrayList<>(current.size() * 2 + 1);
					for (Part part : current) split(part, k, rect, polygon, cuts.size(), next);
					current = next;
				}
				if (current == null) out.add(new Part(r, p, vertices, null));
				else out.addAll(current);
			}
		}
		return out;
	}

	/**
	 * Cuts passed from a part to a sibling part of one primitive (+k front to back, -k back to
	 * front, 1-based); null when the two do not touch.
	 */
	static int @Nullable [] steps(byte @Nullable [] from, byte @Nullable [] to) {
		if (from == null || to == null) return NO_STEPS;
		int[] steps = null;
		int n = 0;
		for (int k = 0; k < from.length; k++) {
			byte a = from[k], b = to[k];
			if (a == b) continue;
			if (a != FRONT && b != FRONT) return null;
			if (a != THROUGH && b != THROUGH) continue;
			if (steps == null) steps = new int[from.length];
			steps[n++] = a == THROUGH ? -(k + 1) : k + 1;
		}
		return steps == null ? NO_STEPS : Arrays.copyOf(steps, n);
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

	private static void split(Part part, int k, IplRouteRect rect, boolean polygon, int openings, List<Part> out) {
		add(out, part, k, clip(part.polygon, rect.frontSpace(), polygon), FRONT, polygon, openings);
		List<IplVertex> rest = clip(part.polygon, rect.backSpace(), polygon);
		if (rect.overOpening(rest)) {
			add(out, part, k, rest, THROUGH, polygon, openings);
			return;
		}
		for (double[] beside : rect.besideSpaces()) {
			if (!valid(rest, polygon)) return;
			add(out, part, k, clip(rest, beside, polygon), BESIDE, polygon, openings);
			rest = clip(rest, negate(beside), polygon);
		}
		add(out, part, k, rest, THROUGH, polygon, openings);
	}

	private static void add(List<Part> out, Part from, int k, List<IplVertex> polygon, byte zone, boolean isPolygon, int openings) {
		if (!valid(polygon, isPolygon)) return;
		byte[] zones;
		if (from.zones == null) {
			zones = new byte[openings];
			Arrays.fill(zones, NONE);
		} else {
			zones = from.zones.clone();
		}
		zones[k] = zone;
		out.add(new Part(from.recording, from.primitive, polygon, zones));
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
}
