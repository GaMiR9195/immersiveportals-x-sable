package ipl.sable.render.route;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which frame each part of a recorded model is drawn in.
 *
 * <p><b>Labels, not planes.</b> Every primitive carries a label kept between render frames
 * ({@link IplRouteMemory}): the frame its centre is in. A label moves to a child frame only when
 * the centre passes a face of the opening front-to-back <em>through its finite area</em> (shape
 * included), and back to the parent only when it passes back through the entry. The test is
 * done in the face's own coordinates between the face's last and current placement, so a portal
 * moving with a body never fakes a crossing. Consequently:
 * <ul>
 *   <li>what passed stays passed wherever it goes afterwards — sideways behind the portal, around
 *       its rim, far away: nothing vanishes at any angle, there is no tube and no infinite
 *       plane;</li>
 *   <li>what went around the rim never counts as passed, even right behind the portal;</li>
 *   <li>the block that holds the model does not decide anything once the model is labelled, so a
 *       holder that goes behind the portal plane, beside it or wholly through it never drops the
 *       routing (the session is held, {@link IplRouteMemory#holding});</li>
 *   <li>two portals at once, nested portals and dimension stacking are all the same rule: a
 *       label is a path in the frame tree.</li>
 * </ul>
 * Primitives without history (a model seen for the first time, a primitive that appeared) are
 * seeded by the holder rule ({@link Seed}), which is right for anything that has not crossed yet.
 * Primitives are matched to the last frame by their index when the layout of the recording is
 * unchanged, otherwise by the nearest previous centre (spatial hash).
 *
 * <p><b>Cutting.</b> A primitive is cut only where it reaches across a face next to its own
 * frame: for a child face, the part behind the face and over the opening goes through; for the
 * entry of its frame, the part in front of the entry and over the opening goes back. A face is
 * considered only from the side the primitive's centre is on, so both faces of a two-sided
 * portal never take the same primitive, and a primitive bent around the rim keeps its frame.
 * Cuts are exact (five planes, then the portal's shape) and recurse into deeper frames.
 */
final class IplRouteSolver {

	/** {@link IplModelRecorder.Recording#primFrame} of a primitive written as cut pieces. */
	static final int CUT = -1;

	/** Distance from a plane below which a vertex counts as lying on it (no slivers). */
	private static final double ON_PLANE = 1.0e-7;
	private static final int INSIDE = 1, OUTSIDE = -1, ACROSS = 0;
	/** A primitive without its old index takes the label of a previous centre this close. */
	private static final double MATCH_RADIUS = 1.0;
	/** Most faces one centre passes in one frame. */
	private static final int MAX_CHAIN = 4;
	private static final int MAX_NEST = 2 * IplRouteFrame.MAX_DEPTH;
	/** Openings this far from the model's geometry cannot be passed by it. */
	private static final double GEOMETRY_MARGIN = 2.0 * IplRouteRect.RIM;
	/** Most openings one model is routed through (cost bound; portal loops). */
	private static final int MAX_OPENINGS = 32;
	private static final Boundary[] NO_BOUNDARIES = new Boundary[0];

	/**
	 * The frames the model is drawn in, and the one frame of the whole model ({@code uniform}
	 * index into {@code frames}) when nothing is cut and every primitive is in it, else -1.
	 */
	record Result(List<IplRouteFrame> frames, int uniform, int parts) {}

	// ------------------------------------------------------------------ faces

	/** A face between {@code from} (the frame whose boundaries list it) and {@code to}. */
	private static final class Boundary {
		final IplRouteRect rect;
		/** The same face one frame earlier (or now, when it is new). */
		final IplRouteRect before;
		final IplRouteFrame to;
		/** Child face (passed front-to-back) or the entry of the frame (passed back-to-front). */
		final boolean forward;
		final IplRouteMemory.RectKey key;
		/** The region that leads to {@code to}, relative to the recording origin. */
		final double[][] region;
		/** Plane of the face relative to the origin: {@code n·l + d} = signed distance. */
		final double nx, ny, nz, d;

		Boundary(IplRouteRect rect, IplRouteRect before, IplRouteFrame to, boolean forward,
				 IplRouteMemory.RectKey key, double ox, double oy, double oz) {
			this.rect = rect;
			this.before = before;
			this.to = to;
			this.forward = forward;
			this.key = key;
			double[][] world = forward ? rect.passageRegion() : rect.frontRegion();
			for (double[] h : world) h[3] += h[0] * ox + h[1] * oy + h[2] * oz;
			this.region = world;
			this.nx = rect.normal.x;
			this.ny = rect.normal.y;
			this.nz = rect.normal.z;
			this.d = -rect.normal.dot(rect.origin) + nx * ox + ny * oy + nz * oz;
		}

		/** Whether the face is seen from {@code (lx, ly, lz)}: the front of a child face, the back of an entry. */
		boolean applies(double lx, double ly, double lz) {
			double s = nx * lx + ny * ly + nz * lz + d;
			return forward ? s >= 0.0 : s < 0.0;
		}
	}

	// ------------------------------------------------------------------ state

	private final List<IplModelRecorder.Recording> recs;
	private final IplModelAnchor anchor;
	private final IplRouteFrame anchorFrame;
	private final IplRouteFrame root;
	private final IplRouteMemory mem;
	private final double ox, oy, oz;
	private final AABB search;
	private final List<IplRouteOpening> openings;
	private final List<IplRouteFrame> frames = new ArrayList<>(4);
	private final Map<IplRouteFrame, Integer> frameIndex = new IdentityHashMap<>(4);
	private final Map<IplRouteFrame, Boundary[]> boundaries = new IdentityHashMap<>(4);
	private final long now = System.nanoTime();
	@Nullable private Seed seed;
	private int parts;

	@Nullable private IplFrameKey lastKey;
	@Nullable private IplRouteFrame lastFrame;
	private boolean lastMissing;

	private IplRouteSolver(IplModelRecorder rec, IplModelAnchor anchor, IplRouteMemory mem, AABB search,
						   List<IplRouteOpening> openings) {
		this.recs = rec.recordings();
		this.anchor = anchor;
		this.anchorFrame = anchor.frame();
		this.root = anchorFrame.root();
		this.mem = mem;
		this.ox = rec.ox;
		this.oy = rec.oy;
		this.oz = rec.oz;
		this.search = search;
		this.openings = openings;
	}

	/** Labels, cuts and remembers the model recorded in {@code rec} (route space bounds {@code box}). */
	static Result solve(IplModelRecorder rec, IplModelAnchor anchor, IplRouteMemory mem, AABB box) {
		if (mem.level != anchor.level()) {
			mem.reset();
			mem.level = anchor.level();
		}
		IplRouteFrame root = anchor.frame().root();
		boolean teleported = rebaseIfTeleported(mem, anchor, root);
		AABB search = !teleported && mem.box != null ? box.minmax(mem.box) : box;
		List<IplRouteFrame> starts = new ArrayList<>(2);
		starts.add(anchor.frame());
		IplFrameKey last = null;
		for (int i = 0; i < mem.units; i++) {
			IplFrameKey k = mem.labels[i];
			if (k == null || k == last) continue;
			last = k;
			IplRouteFrame f = root.resolveOrAncestor(k);
			if (!starts.contains(f)) starts.add(f);
		}
		return new IplRouteSolver(rec, anchor, mem, search, openingsNear(starts, search)).run(box);
	}

	/**
	 * The openings the geometry in {@code box} may pass: those of the start frames (the holder's
	 * and every labelled one) and of their ancestors, and of every frame those lead to, as long
	 * as they come near the geometry.
	 */
	private static List<IplRouteOpening> openingsNear(List<IplRouteFrame> starts, AABB box) {
		List<IplRouteOpening> out = new ArrayList<>();
		Set<IplRouteFrame> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IplRouteFrame> queue = new ArrayDeque<>();
		for (IplRouteFrame s : starts) for (IplRouteFrame f = s; f != null; f = f.parent) queue.add(f);
		while (!queue.isEmpty() && out.size() < MAX_OPENINGS) {
			IplRouteFrame frame = queue.poll();
			if (!visited.add(frame)) continue;
			for (IplOpening opening : IplOpenings.of(frame.level)) {
				IplRouteRect rect = frame.rectOf(opening);
				if (!rect.near(box, GEOMETRY_MARGIN)) continue;
				out.add(new IplRouteOpening(frame, opening, rect));
				IplRouteFrame next = frame.through(opening);
				if (next != null && !visited.contains(next)) queue.add(next);
				if (out.size() == MAX_OPENINGS) break;
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ run

	private Result run(AABB box) {
		int total = 0;
		for (IplModelRecorder.Recording r : recs) total += r.units();
		mem.ensureNext(total);
		Grid grid = null;
		// How far the holder moved since the last solve: a model on a moving body moves with it,
		// so previous centres are compared after that shift.
		double mx = 0.0, my = 0.0, mz = 0.0;
		if (mem.units > 0 && mem.anchorKey != null) {
			Vector3d a = anchor.point();
			mx = a.x - mem.ax;
			my = a.y - mem.ay;
			mz = a.z - mem.az;
		}
		double slack = MATCH_RADIUS + 2.0 * Math.sqrt(mx * mx + my * my + mz * mz);
		double slackSq = slack * slack;
		double[] prevCentres = mem.centroids;
		boolean uniform = true, holding = false;
		int uniformIndex = -1;
		int g = 0, prevOffset = 0;
		for (int r = 0; r < recs.size(); r++) {
			IplModelRecorder.Recording rc = recs.get(r);
			int units = rc.units();
			rc.piecesSize = 0;
			if (rc.primFrame.length < units) rc.primFrame = new int[Math.max(units, rc.primFrame.length * 2)];
			boolean same = r < mem.recordings && mem.types[r] == rc.type && mem.unitCounts[r] == units;
			int size = rc.primitiveSize;
			float[] pos = rc.pos;
			for (int p = 0; p < units; p++, g++) {
				// Centre of the unit, relative to the origin.
				int from = size == 0 ? 0 : p * size, to = size == 0 ? rc.count : from + size;
				double cx = 0.0, cy = 0.0, cz = 0.0;
				for (int i = from * 3, end = to * 3; i < end; i += 3) {
					cx += pos[i];
					cy += pos[i + 1];
					cz += pos[i + 2];
				}
				double inv = 1.0 / (to - from);
				cx *= inv;
				cy *= inv;
				cz *= inv;
				double wx = ox + cx, wy = oy + cy, wz = oz + cz;

				int prev = -1;
				if (same) {
					// Same layout: the same primitive, unless the renderer reordered its output.
					prev = prevOffset + p;
					double ex = prevCentres[prev * 3] + mx - wx, ey = prevCentres[prev * 3 + 1] + my - wy, ez = prevCentres[prev * 3 + 2] + mz - wz;
					if (ex * ex + ey * ey + ez * ez > slackSq) prev = -1;
				}
				if (prev < 0 && mem.units > 0) {
					if (grid == null) grid = new Grid(mem.centroids, mem.units);
					prev = grid.nearest(wx - mx, wy - my, wz - mz);
				}
				IplFrameKey key = prev >= 0 ? mem.labels[prev] : null;
				IplRouteFrame label;
				IplFrameKey keep;
				if (key == null) {
					label = seed().frameAt(wx, wy, wz);
					keep = label.key;
				} else {
					label = resolve(key);
					if (lastMissing) {
						keep = key;
					} else {
						double[] c = mem.centroids;
						label = sweep(label, c[prev * 3], c[prev * 3 + 1], c[prev * 3 + 2], wx, wy, wz);
						keep = label.key;
					}
				}
				mem.nextLabels[g] = keep;
				mem.nextCentroids[g * 3] = wx;
				mem.nextCentroids[g * 3 + 1] = wy;
				mem.nextCentroids[g * 3 + 2] = wz;
				if (label != anchorFrame || keep != label.key) holding = true;

				int placed;
				if (size == 0) {
					placed = index(label);
					rc.primFrame[0] = placed;
					parts++;
				} else {
					placed = place(rc, p, label, cx, cy, cz);
				}
				if (placed == CUT || uniformIndex >= 0 && placed != uniformIndex) uniform = false;
				else uniformIndex = placed;
			}
			if (r < mem.recordings) prevOffset += mem.unitCounts[r];
		}
		remember(box, total, holding);
		// Nothing complete was written: the model is what its holder's frame shows.
		if (total == 0) return new Result(frames, index(anchorFrame), parts);
		return new Result(frames, uniform ? uniformIndex : -1, parts);
	}

	private void remember(AABB box, int total, boolean holding) {
		int n = recs.size();
		if (mem.types.length < n) {
			mem.types = Arrays.copyOf(mem.types, Math.max(n, mem.types.length * 2));
			mem.unitCounts = Arrays.copyOf(mem.unitCounts, mem.types.length);
		}
		for (int r = 0; r < n; r++) {
			mem.types[r] = recs.get(r).type;
			mem.unitCounts[r] = recs.get(r).units();
		}
		for (int r = n; r < mem.types.length && mem.types[r] != null; r++) mem.types[r] = null;
		mem.recordings = n;
		for (Boundary[] bs : boundaries.values()) for (Boundary b : bs) mem.nextRects.put(b.key, b.rect);
		mem.swap(total);
		mem.anchorKey = anchorFrame.key;
		Vector3d a = anchor.point();
		mem.ax = a.x;
		mem.ay = a.y;
		mem.az = a.z;
		mem.box = box;
		mem.holding = holding;
	}

	private int index(IplRouteFrame frame) {
		Integer i = frameIndex.get(frame);
		if (i != null) return i;
		frames.add(frame);
		frameIndex.put(frame, frames.size() - 1);
		return frames.size() - 1;
	}

	// ------------------------------------------------------------------ labels

	/** The frame of a remembered label; sets {@link #lastMissing} while its path is held unresolved. */
	private IplRouteFrame resolve(IplFrameKey key) {
		if (key == lastKey && lastFrame != null) return lastFrame;
		IplRouteFrame f = root.resolve(key);
		boolean missing = false;
		if (f == null) {
			Long since = mem.missingSince.get(key);
			if (since == null) mem.missingSince.put(key, since = now);
			if (now - since < IplRouteMemory.MISSING_HOLD_NANOS) missing = true;
			else mem.missingSince.remove(key);
			f = root.resolveOrAncestor(key);
		} else if (!mem.missingSince.isEmpty()) {
			mem.missingSince.remove(key);
		}
		lastKey = key;
		lastFrame = f;
		lastMissing = missing;
		return f;
	}

	/** The frame after the centre moved from {@code (x0..)} to {@code (x1..)}: every face it passed, in order. */
	private IplRouteFrame sweep(IplRouteFrame label, double x0, double y0, double z0, double x1, double y1, double z1) {
		if (x0 == x1 && y0 == y1 && z0 == z1) return label;
		IplRouteFrame f = label;
		double after = -1.0;
		for (int chain = 0; chain < MAX_CHAIN; chain++) {
			Boundary best = null;
			double bestT = 2.0;
			for (Boundary b : boundaries(f)) {
				double t = b.rect.crossing(b.before, x0, y0, z0, x1, y1, z1, b.forward);
				if (t > after && t < bestT) {
					best = b;
					bestT = t;
				}
			}
			if (best == null) break;
			f = best.to;
			after = bestT;
		}
		return f;
	}

	/** The faces next to {@code frame}: its child faces near the model and its own entry. */
	private Boundary[] boundaries(IplRouteFrame frame) {
		Boundary[] known = boundaries.get(frame);
		if (known != null) return known;
		List<Boundary> list = new ArrayList<>(2);
		for (IplRouteOpening o : openings) {
			if (o.frame() != frame || frame.isExit(o.opening())) continue;
			IplRouteFrame to = frame.through(o.opening());
			if (to == null || to.parent != frame) continue;
			list.add(boundary(o.rect(), frame.key, o.opening().id, to, true));
		}
		IplRouteRect entry = frame.entryRect();
		if (entry != null && frame.parent != null && frame.entry != null && entry.near(search, GEOMETRY_MARGIN)) {
			list.add(boundary(entry, frame.parent.key, frame.entry.id, frame.parent, false));
		}
		Boundary[] out = list.isEmpty() ? NO_BOUNDARIES : list.toArray(NO_BOUNDARIES);
		boundaries.put(frame, out);
		return out;
	}

	private Boundary boundary(IplRouteRect rect, IplFrameKey frame, UUID opening, IplRouteFrame to, boolean forward) {
		IplRouteMemory.RectKey key = new IplRouteMemory.RectKey(frame, opening);
		IplRouteRect before = mem.rects.get(key);
		return new Boundary(rect, before != null ? before : rect, to, forward, key, ox, oy, oz);
	}

	// ------------------------------------------------------------------ placing

	/** Places primitive {@code p} labelled {@code label}; its frame index, or {@link #CUT}. */
	private int place(IplModelRecorder.Recording rc, int p, IplRouteFrame label, double cx, double cy, double cz) {
		Boundary[] bs = boundaries(label);
		int size = rc.primitiveSize, base = p * size;
		for (int k = 0; k < bs.length; k++) {
			Boundary b = bs[k];
			if (!b.applies(cx, cy, cz) || classify(rc, null, base, size, b.region) == OUTSIDE) continue;
			int[] poly = new int[size];
			for (int i = 0; i < size; i++) poly[i] = base + i;
			// Slivers are dropped: a primitive whose every piece is degenerate draws nothing.
			rc.primFrame[p] = CUT;
			placePiece(rc, p, poly, label, null, cx, cy, cz, k, 0, true);
			return rc.primFrame[p];
		}
		int fi = index(label);
		rc.primFrame[p] = fi;
		parts++;
		return fi;
	}

	/**
	 * Places {@code poly} (a piece of primitive {@code prim}) seen from {@code (rx, ry, rz)} in
	 * {@code frame}, testing its faces from {@code first} on; {@code came} is the frame it came from.
	 */
	private void placePiece(IplModelRecorder.Recording rc, int prim, int[] poly, IplRouteFrame frame,
							@Nullable IplRouteFrame came, double rx, double ry, double rz, int first, int nest, boolean whole) {
		if (nest < MAX_NEST) {
			Boundary[] bs = boundaries(frame);
			boolean polygon = rc.primitiveSize >= 3;
			for (int k = first; k < bs.length; k++) {
				Boundary b = bs[k];
				if (b.to == came || !b.applies(rx, ry, rz)) continue;
				int cls = classify(rc, poly, 0, poly.length, b.region);
				if (cls == OUTSIDE) continue;
				if (cls == INSIDE) {
					double[] c = centre(rc, poly);
					if (!overShape(b, c)) continue;
					placePiece(rc, prim, poly, b.to, frame, c[0], c[1], c[2], 0, nest + 1, whole);
					return;
				}
				// Across: the part inside goes on, the parts outside stay with the remaining faces.
				int[] rest = poly;
				for (double[] h : b.region) {
					double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
					for (int v : rest) {
						double dv = distance(rc.pos, v, h);
						if (dv < min) min = dv;
						if (dv > max) max = dv;
					}
					if (min >= -ON_PLANE) continue;
					if (max <= ON_PLANE) {
						placePiece(rc, prim, rest, frame, came, rx, ry, rz, k + 1, nest, false);
						rest = null;
						break;
					}
					int[] outside = clip(rc, rest, h, -1.0, polygon);
					if (valid(outside, polygon)) placePiece(rc, prim, outside, frame, came, rx, ry, rz, k + 1, nest, false);
					rest = clip(rc, rest, h, 1.0, polygon);
					if (!valid(rest, polygon)) {
						rest = null;
						break;
					}
				}
				if (rest != null) {
					double[] c = centre(rc, rest);
					if (overShape(b, c)) placePiece(rc, prim, rest, b.to, frame, c[0], c[1], c[2], 0, nest + 1, false);
					else placePiece(rc, prim, rest, frame, came, rx, ry, rz, k + 1, nest, false);
				}
				return;
			}
		}
		int fi = index(frame);
		parts++;
		if (whole) {
			rc.primFrame[prim] = fi;
			return;
		}
		rc.primFrame[prim] = CUT;
		int need = rc.piecesSize + 3 + poly.length;
		if (rc.pieces.length < need) rc.pieces = Arrays.copyOf(rc.pieces, Math.max(need, rc.pieces.length * 2));
		int[] out = rc.pieces;
		int at = rc.piecesSize;
		out[at] = prim;
		out[at + 1] = fi;
		out[at + 2] = poly.length;
		System.arraycopy(poly, 0, out, at + 3, poly.length);
		rc.piecesSize = need;
	}

	private boolean overShape(Boundary b, double[] c) {
		return b.rect.opening.rectangular || b.rect.over(ox + c[0], oy + c[1], oz + c[2]);
	}

	private static double[] centre(IplModelRecorder.Recording rc, int[] poly) {
		double x = 0.0, y = 0.0, z = 0.0;
		float[] pos = rc.pos;
		for (int v : poly) {
			x += pos[v * 3];
			y += pos[v * 3 + 1];
			z += pos[v * 3 + 2];
		}
		double inv = 1.0 / poly.length;
		return new double[]{x * inv, y * inv, z * inv};
	}

	/**
	 * Wholly inside the region, wholly outside one of its half-spaces, or across its boundary.
	 * The piece is {@code poly} or, when null, the vertices {@code [base, base + n)}.
	 */
	private static int classify(IplModelRecorder.Recording rc, @Nullable int[] poly, int base, int n, double[][] region) {
		boolean across = false;
		float[] pos = rc.pos;
		for (double[] h : region) {
			double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < n; i++) {
				double d = distance(pos, poly == null ? base + i : poly[i], h);
				if (d < min) min = d;
				if (d > max) max = d;
			}
			if (max <= ON_PLANE) return OUTSIDE;
			if (min < -ON_PLANE) across = true;
		}
		return across ? ACROSS : INSIDE;
	}

	private static boolean valid(int[] part, boolean polygon) {
		return part.length >= (polygon ? 3 : 2);
	}

	/** The part of {@code in} where {@code sign * plane} is non-negative (new vertices appended to {@code rc}). */
	private static int[] clip(IplModelRecorder.Recording rc, int[] in, double[] plane, double sign, boolean polygon) {
		int n = in.length;
		int[] out = new int[n + 2];
		int m = 0;
		int edges = polygon ? n : 1;
		for (int i = 0; i < edges; i++) {
			int p = in[i], q = in[(i + 1) % n];
			double dp = sign * distance(rc.pos, p, plane), dq = sign * distance(rc.pos, q, plane);
			boolean pin = dp >= 0.0, qin = dq >= 0.0;
			if (pin) out[m++] = p;
			if (pin != qin) out[m++] = rc.lerp(p, q, dp / (dp - dq));
			if (!polygon && qin) out[m++] = q;
		}
		return m == out.length ? out : Arrays.copyOf(out, m);
	}

	private static double distance(float[] pos, int v, double[] p) {
		int i = v * 3;
		return p[0] * pos[i] + p[1] * pos[i + 1] + p[2] * pos[i + 2] + p[3];
	}

	// ------------------------------------------------------------------ teleport

	/**
	 * The holder moved to another frame by teleporting with its body (a flip through a portal of
	 * its own level): every label and centre is re-expressed from the holder's new place, so the
	 * parts stay exactly where they were drawn. A holder that merely crossed (its block passed,
	 * no jump) changes nothing.
	 */
	private static boolean rebaseIfTeleported(IplRouteMemory mem, IplModelAnchor anchor, IplRouteFrame root) {
		IplFrameKey old = mem.anchorKey;
		IplRouteFrame now = anchor.frame();
		if (old == null || mem.units == 0 || old.equals(now.key)) return false;
		IplRouteFrame was = root.resolve(old);
		if (was == null || was.parent == null || was.level != root.level) return false;
		Vector3d p = anchor.point();
		Vector3d mapped = was.toLevel.transformPosition(new Vector3d(mem.ax, mem.ay, mem.az));
		if (!(mapped.distanceSquared(p) < p.distanceSquared(mem.ax, mem.ay, mem.az))) return false;
		Map<IplFrameKey, IplFrameKey> moved = new HashMap<>();
		Matrix4d m = was.toLevel;
		Vector3d c = new Vector3d();
		double[] cs = mem.centroids;
		for (int i = 0; i < mem.units; i++) {
			IplFrameKey k = mem.labels[i];
			if (k != null) {
				IplFrameKey nk;
				if (moved.containsKey(k)) nk = moved.get(k);
				else {
					IplRouteFrame f = reexpress(root, was, k);
					nk = f == null ? null : f.key;
					moved.put(k, nk);
				}
				mem.labels[i] = nk;
			}
			m.transformPosition(cs[i * 3], cs[i * 3 + 1], cs[i * 3 + 2], c);
			cs[i * 3] = c.x;
			cs[i * 3 + 1] = c.y;
			cs[i * 3 + 2] = c.z;
		}
		m.transformPosition(mem.ax, mem.ay, mem.az, c);
		mem.ax = c.x;
		mem.ay = c.y;
		mem.az = c.z;
		mem.rects.clear();
		mem.missingSince.clear();
		mem.box = null;
		return true;
	}

	/** {@code key}, a path from the root seen when the holder was in {@code was}, as a path from the root now. */
	@Nullable
	private static IplRouteFrame reexpress(IplRouteFrame root, IplRouteFrame was, IplFrameKey key) {
		IplFrameKey common = IplFrameKey.common(was.key, key);
		IplRouteFrame f = root;
		for (IplRouteFrame w = was; w != null && w.depth > common.depth; w = w.parent) {
			if (w.entry == null) return null;
			f = f.back(w.entry);
			if (f == null) return null;
		}
		int n = key.depth - common.depth;
		UUID[] down = new UUID[n];
		IplFrameKey k = key;
		for (int i = n - 1; i >= 0; i--) {
			down[i] = k.entry;
			k = k.parent;
		}
		for (UUID id : down) {
			IplOpening o = id == null ? null : IplOpenings.byId(f.level, id);
			f = o == null ? null : f.through(o);
			if (f == null) return null;
		}
		return f;
	}

	// ------------------------------------------------------------------ matching

	/** Previous centres hashed by cell: nearest one within {@link #MATCH_RADIUS}. */
	private static final class Grid {
		private final Long2IntOpenHashMap head = new Long2IntOpenHashMap();
		private final int[] next;
		private final double[] c;

		Grid(double[] centres, int n) {
			this.c = centres;
			this.next = new int[n];
			head.defaultReturnValue(-1);
			for (int i = 0; i < n; i++) {
				next[i] = head.put(key(cell(c[i * 3]), cell(c[i * 3 + 1]), cell(c[i * 3 + 2])), i);
			}
		}

		int nearest(double x, double y, double z) {
			int cx = cell(x), cy = cell(y), cz = cell(z);
			int best = -1;
			double bestD = MATCH_RADIUS * MATCH_RADIUS;
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				for (int i = head.get(key(cx + dx, cy + dy, cz + dz)); i >= 0; i = next[i]) {
					double ex = c[i * 3] - x, ey = c[i * 3 + 1] - y, ez = c[i * 3 + 2] - z;
					double d = ex * ex + ey * ey + ez * ez;
					if (d <= bestD) {
						bestD = d;
						best = i;
					}
				}
			}
			return best;
		}

		private static int cell(double v) {
			return (int) Math.floor(v / MATCH_RADIUS);
		}

		private static long key(int x, int y, int z) {
			return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | (long) z & 0x1FFFFFL;
		}
	}

	// ------------------------------------------------------------------ seed

	private Seed seed() {
		if (seed == null) seed = new Seed(openings, anchor.point(), anchorFrame);
		return seed;
	}

	/**
	 * The frame of a primitive that has no history, from the block that holds the model. Every
	 * physical cut (coincident faces, {@link IplRouteCut}) is passed through ONE face: the face the
	 * holder already passed, else the face whose front holds it (the side of a two-sided portal),
	 * else for deeper frames the face whose front holds the point where that frame is entered.
	 * The face's passage — behind it and over its opening — is its far frame. Right for anything
	 * that has not crossed yet; history takes over from the next frame on.
	 */
	private static final class Seed {
		private record Passage(IplRouteRect face, double[][] region, IplRouteFrame outer, IplRouteFrame inner) {
			boolean contains(double x, double y, double z) {
				for (double[] h : region) if (h[0] * x + h[1] * y + h[2] * z + h[3] < 0.0) return false;
				return face.opening.rectangular || face.over(x, y, z);
			}
		}

		private final List<Passage> passages;
		private final IplRouteFrame anchorFrame;

		Seed(List<IplRouteOpening> openings, Vector3d anchor, IplRouteFrame anchorFrame) {
			this.anchorFrame = anchorFrame;
			this.passages = passages(IplRouteCut.of(openings), openings, anchor, anchorFrame);
		}

		IplRouteFrame frameAt(double x, double y, double z) {
			IplRouteFrame frame = anchorFrame;
			for (Passage p : passages) {
				if (frame == p.outer || frame == p.inner) frame = p.contains(x, y, z) ? p.inner : p.outer;
			}
			return frame;
		}

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
			deeper.sort(Comparator.comparingInt(p -> p.outer().depth));
			own.addAll(deeper);
			return own;
		}

		private static Vector3d entryPoint(IplRouteFrame frame, Vector3d anchor) {
			if (frame.parent == null || frame.entry == null) return anchor;
			return frame.parent.rectOf(frame.entry).origin;
		}
	}
}
