package ipl.sable.render.route;

import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which frame each part of a recorded model is drawn in.
 *
 * <p><b>Tethered to the holder.</b> A model is one thing hanging on the block that holds it, so
 * the frame of every primitive is found by walking the model from its holder along its own
 * geometry ({@link IplRouteTopology}): the walk starts in the holder's frame (the piece of its
 * block), and a step between two touching primitives moves into a child frame only when the
 * segment between their centres passes a face of the opening front-to-back <em>through its
 * finite area</em> (shape included), and back to the parent only when it passes back through the
 * entry. Primitives are reached in breadth-first order, so each one takes the frame of its
 * shortest connection to the holder. Consequently:
 * <ul>
 *   <li>a part is always where its connection to the holder puts it: what went in is drawn
 *       beyond the exit while it is connected through the opening (sideways behind the portal,
 *       around the exit's rim, far away), and comes back the moment the model is pulled back out,
 *       however it moves; nothing is ever left hanging at the entrance or beyond the exit;</li>
 *   <li>what went around the rim never counts as passed, and a part sticking out beside the rim
 *       of a holder that went through follows the holder (it wraps around the exit) instead of
 *       staying behind as a separate piece;</li>
 *   <li>the walk is stateless: no label can go stale, a holder flip or an unloaded portal needs
 *       no re-expression, and fast motion cannot skip a crossing;</li>
 *   <li>two portals at once, nested portals and dimension stacking are the same rule: a frame
 *       is a path in the frame tree and a walk may cross several faces.</li>
 * </ul>
 * A model whose geometry stays on the holder's side of every face of the holder's frame is not
 * walked at all.
 *
 * <p><b>Cutting.</b> A primitive is cut only where it reaches across a face next to its own
 * frame: for a child face, the part behind the face and over the opening goes through; for the
 * entry of its frame, the part in front of the entry and over the opening goes back. A face is
 * considered only from the side the primitive's centre is on, so both faces of a two-sided
 * portal never take the same primitive, and a primitive bent around the rim keeps its frame.
 * Cuts are exact (five planes, then the portal's shape) and recurse into deeper frames; cut
 * polygons live in a shared index arena, so cutting allocates nothing per primitive.
 */
final class IplRouteSolver {

	/** {@link IplModelRecorder.Recording#primFrame} of a primitive written as cut pieces. */
	static final int CUT = -1;

	/** Distance from a plane below which a vertex counts as lying on it (no slivers). */
	private static final double ON_PLANE = 1.0e-7;
	private static final int INSIDE = 1, OUTSIDE = -1, ACROSS = 0;
	/** Most faces one step of the walk passes. */
	private static final int MAX_CHAIN = 4;
	private static final int MAX_NEST = 2 * IplRouteFrame.MAX_DEPTH;
	/** Openings this far from the model's geometry cannot be passed by it. */
	private static final double GEOMETRY_MARGIN = 2.0 * IplRouteRect.RIM;
	/** Most openings one model is routed through (cost bound; portal loops). */
	private static final int MAX_OPENINGS = 32;
	/** A walked edge longer than this multiple of its built length (plus slack) is torn. */
	private static final double STRETCH_FACTOR = 4.0, STRETCH_SLACK = 1.0;
	private static final Boundary[] NO_BOUNDARIES = new Boundary[0];

	// Scratch shared by every solve (render thread only; solves never nest).
	private static int[] queue = new int[256];
	private static IplRouteFrame[] walked = new IplRouteFrame[256];
	/** Index arena of cut polygons, used as a stack. */
	private static int[] arena = new int[1024];
	private static int top;
	/** Length of the polygon the last {@link #clip} wrote. */
	private static int clipped;
	private static double cenX, cenY, cenZ;

	/**
	 * The frames the model is drawn in, and the one frame of the whole model ({@code uniform}
	 * index into {@code frames}) when nothing is cut and every primitive is in it, else -1.
	 */
	record Result(List<IplRouteFrame> frames, int uniform, int parts) {}

	// ------------------------------------------------------------------ faces

	/** A face between {@code from} (the frame whose boundaries list it) and {@code to}. */
	private static final class Boundary {
		final IplRouteRect rect;
		final IplRouteFrame to;
		/** Child face (passed front-to-back) or the entry of the frame (passed back-to-front). */
		final boolean forward;
		/** The region that leads to {@code to}, relative to the recording origin. */
		final double[][] region;
		/** Plane of the face relative to the origin: {@code n·l + d} = signed distance. */
		final double nx, ny, nz, d;

		Boundary(IplRouteRect rect, IplRouteFrame to, boolean forward, double ox, double oy, double oz) {
			this.rect = rect;
			this.to = to;
			this.forward = forward;
			double[][] world = forward ? rect.passageRegion() : rect.frontRegion();
			for (double[] h : world) h[3] += h[0] * ox + h[1] * oy + h[2] * oz;
			this.region = world;
			this.nx = rect.normal.x;
			this.ny = rect.normal.y;
			this.nz = rect.normal.z;
			this.d = -rect.normal.dot(rect.origin) + nx * ox + ny * oy + nz * oz;
		}

		/** Whether this face is considered for a primitive centred at the local point. */
		boolean applies(double lx, double ly, double lz) {
			double s = nx * lx + ny * ly + nz * lz + d;
			return forward ? s >= 0.0 : s < 0.0;
		}

		/** Whether a route-space point lies on the side of the plane this face leads to. */
		boolean away(double x, double y, double z) {
			double s = rect.side(x, y, z);
			return forward ? s < 0.0 : s >= 0.0;
		}
	}

	// ------------------------------------------------------------------ solve

	private final List<IplModelRecorder.Recording> recs;
	private final IplModelAnchor anchor;
	private final IplRouteFrame anchorFrame;
	private final IplRouteMemory mem;
	private final double ox, oy, oz;
	private final AABB search;
	private final List<IplRouteOpening> openings;
	private final List<IplRouteFrame> frames = new ArrayList<>(4);
	private final Map<IplRouteFrame, Integer> frameIndex = new IdentityHashMap<>(4);
	private final Map<IplRouteFrame, Boundary[]> boundaries = new IdentityHashMap<>(4);
	@Nullable private IplRouteFrame lastBoundsFrame;
	private Boundary[] lastBounds = NO_BOUNDARIES;
	private int parts;

	private IplRouteSolver(IplModelRecorder rec, IplModelAnchor anchor, IplRouteMemory mem, AABB search,
						   List<IplRouteOpening> openings) {
		this.recs = rec.recordings();
		this.anchor = anchor;
		this.anchorFrame = anchor.frame();
		this.mem = mem;
		this.ox = rec.ox;
		this.oy = rec.oy;
		this.oz = rec.oz;
		this.search = search;
		this.openings = openings;
	}

	/** Routes one recorded model; {@code box} is its geometry in route space. */
	static Result solve(IplModelRecorder rec, IplModelAnchor anchor, IplRouteMemory mem, AABB box) {
		if (mem.level != anchor.level()) {
			mem.reset();
			mem.level = anchor.level();
		}
		List<IplRouteOpening> openings = openingsNear(List.of(anchor.frame()), box);
		return new IplRouteSolver(rec, anchor, mem, box, openings).run();
	}

	/**
	 * The openings the geometry in {@code box} may pass: those of the holder's frame and its
	 * ancestors, then of every frame behind an opening found, breadth-first.
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

	private Result run() {
		top = 0;
		int total = 0;
		for (IplModelRecorder.Recording r : recs) total += r.units();
		boolean layout = sameLayout();
		if (!layout) mem.topology.invalidate();
		boolean measure = !mem.topology.valid(total);
		mem.ensure(total);
		double[] c = mem.centroids;
		float[] radius = mem.radii;

		// Unit centres in route space (and radii when the touch graph must be rebuilt).
		int g = 0;
		for (IplModelRecorder.Recording rc : recs) {
			int units = rc.units(), size = rc.primitiveSize;
			float[] pos = rc.pos;
			for (int p = 0; p < units; p++, g++) {
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
				c[g * 3] = ox + cx;
				c[g * 3 + 1] = oy + cy;
				c[g * 3 + 2] = oz + cz;
				if (measure) {
					double r2 = 0.0;
					for (int i = from * 3, end = to * 3; i < end; i += 3) {
						double ex = pos[i] - cx, ey = pos[i + 1] - cy, ez = pos[i + 2] - cz;
						double e = ex * ex + ey * ey + ez * ez;
						if (e > r2) r2 = e;
					}
					radius[g] = (float) Math.sqrt(r2);
				}
			}
		}
		if (!layout) rememberLayout();

		// Walk only when something lies beyond a face of the holder's frame.
		IplRouteFrame[] fr = null;
		if (total > 0 && leavesHome(c, total)) {
			if (measure) mem.topology.build(c, radius, total);
			fr = tether(c, total);
		}

		boolean uniform = true, holding = false;
		int uniformIndex = -1;
		g = 0;
		for (IplModelRecorder.Recording rc : recs) {
			int units = rc.units();
			rc.piecesSize = 0;
			if (rc.primFrame.length < units) rc.primFrame = new int[Math.max(units, rc.primFrame.length * 2)];
			for (int p = 0; p < units; p++, g++) {
				IplRouteFrame label = fr == null ? anchorFrame : fr[g];
				if (label != anchorFrame) holding = true;
				int placed;
				if (rc.primitiveSize == 0) {
					placed = index(label);
					rc.primFrame[0] = placed;
					parts++;
				} else {
					placed = place(rc, p, label, c[g * 3] - ox, c[g * 3 + 1] - oy, c[g * 3 + 2] - oz);
				}
				if (placed == CUT || uniformIndex >= 0 && placed != uniformIndex) uniform = false;
				else uniformIndex = placed;
			}
		}
		if (fr != null) Arrays.fill(fr, 0, total, null);
		mem.holding = holding;
		if (total == 0) return new Result(frames, index(anchorFrame), parts);
		return new Result(frames, uniform ? uniformIndex : -1, parts);
	}

	private boolean sameLayout() {
		int n = recs.size();
		if (n != mem.recordings) return false;
		for (int r = 0; r < n; r++) {
			IplModelRecorder.Recording rc = recs.get(r);
			if (mem.types[r] != rc.type || mem.unitCounts[r] != rc.units()) return false;
		}
		return true;
	}

	private void rememberLayout() {
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
	}

	/** Whether the holder or any unit lies beyond a face of the holder's frame (else no walk can leave it). */
	private boolean leavesHome(double[] c, int n) {
		Boundary[] home = boundaries(anchorFrame);
		if (home.length == 0) return false;
		Vector3d a = anchor.point();
		for (Boundary b : home) {
			if (b.away(a.x, a.y, a.z)) return true;
			for (int i = 0; i < n; i++) if (b.away(c[i * 3], c[i * 3 + 1], c[i * 3 + 2])) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ walk

	/** Frames of all units: breadth-first from the unit nearest the holder, along the touch graph. */
	private IplRouteFrame[] tether(double[] c, int n) {
		IplRouteTopology t = mem.topology;
		if (walked.length < n) walked = new IplRouteFrame[Math.max(n, walked.length * 2)];
		if (queue.length < n) queue = new int[Math.max(n, queue.length * 2)];
		IplRouteFrame[] fr = walked;
		int[] q = queue;
		Vector3d a = anchor.point();
		int root = 0;
		double best = Double.POSITIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			double ex = c[i * 3] - a.x, ey = c[i * 3 + 1] - a.y, ez = c[i * 3 + 2] - a.z;
			double d = ex * ex + ey * ey + ez * ez;
			if (d < best) {
				best = d;
				root = i;
			}
		}
		fr[root] = walk(anchorFrame, a.x, a.y, a.z, c[root * 3], c[root * 3 + 1], c[root * 3 + 2]);
		q[0] = root;
		int head = 0, tail = 1, stretched = 0;
		int[] start = t.start, adj = t.adj;
		float[] length = t.length;
		while (head < tail) {
			int i = q[head++];
			IplRouteFrame f = fr[i];
			double xi = c[i * 3], yi = c[i * 3 + 1], zi = c[i * 3 + 2];
			for (int k = start[i], end = start[i + 1]; k < end; k++) {
				int j = adj[k];
				if (fr[j] != null) continue;
				double xj = c[j * 3], yj = c[j * 3 + 1], zj = c[j * 3 + 2];
				double ex = xj - xi, ey = yj - yi, ez = zj - zi;
				double limit = STRETCH_FACTOR * length[k] + STRETCH_SLACK;
				if (ex * ex + ey * ey + ez * ez > limit * limit) stretched++;
				fr[j] = walk(f, xi, yi, zi, xj, yj, zj);
				q[tail++] = j;
			}
		}
		if (tail < n) {
			// Not reachable (a bounded bridge gave up): straight from the holder.
			for (int i = 0; i < n; i++) {
				if (fr[i] == null) fr[i] = walk(anchorFrame, a.x, a.y, a.z, c[i * 3], c[i * 3 + 1], c[i * 3 + 2]);
			}
		}
		// The model was torn apart since the graph was built: rebuild it next frame.
		if (stretched > 2 && stretched * 16 > n) t.invalidate();
		return fr;
	}

	/** The frame reached from {@code label} by the segment between two route-space points. */
	private IplRouteFrame walk(IplRouteFrame label, double x0, double y0, double z0, double x1, double y1, double z1) {
		IplRouteFrame f = label;
		double after = -1.0;
		for (int chain = 0; chain < MAX_CHAIN; chain++) {
			Boundary[] bs = boundaries(f);
			if (bs.length == 0) break;
			Boundary best = null;
			double bestT = 2.0;
			for (Boundary b : bs) {
				double t = b.rect.crossing(b.rect, x0, y0, z0, x1, y1, z1, b.forward);
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

	private int index(IplRouteFrame frame) {
		Integer i = frameIndex.get(frame);
		if (i != null) return i;
		frames.add(frame);
		frameIndex.put(frame, frames.size() - 1);
		return frames.size() - 1;
	}

	/** The faces next to {@code frame}: its child faces near the model and its own entry. */
	private Boundary[] boundaries(IplRouteFrame frame) {
		if (frame == lastBoundsFrame) return lastBounds;
		Boundary[] known = boundaries.get(frame);
		if (known == null) {
			List<Boundary> list = new ArrayList<>(2);
			for (IplRouteOpening o : openings) {
				if (o.frame() != frame || frame.isExit(o.opening())) continue;
				IplRouteFrame to = frame.through(o.opening());
				if (to == null || to.parent != frame) continue;
				list.add(new Boundary(o.rect(), to, true, ox, oy, oz));
			}
			IplRouteRect entry = frame.entryRect();
			if (entry != null && frame.parent != null && frame.entry != null && entry.near(search, GEOMETRY_MARGIN)) {
				list.add(new Boundary(entry, frame.parent, false, ox, oy, oz));
			}
			known = list.isEmpty() ? NO_BOUNDARIES : list.toArray(NO_BOUNDARIES);
			boundaries.put(frame, known);
		}
		lastBoundsFrame = frame;
		lastBounds = known;
		return known;
	}

	// ------------------------------------------------------------------ cutting

	/** Places primitive {@code p} of its frame {@code label}: whole, or as cut pieces. */
	private int place(IplModelRecorder.Recording rc, int p, IplRouteFrame label, double cx, double cy, double cz) {
		Boundary[] bs = boundaries(label);
		int size = rc.primitiveSize, base = p * size;
		for (int k = 0; k < bs.length; k++) {
			Boundary b = bs[k];
			if (!b.applies(cx, cy, cz) || classify(rc.pos, null, base, size, b.region) == OUTSIDE) continue;
			int mark = top;
			int off = alloc(size);
			for (int i = 0; i < size; i++) arena[off + i] = base + i;
			rc.primFrame[p] = CUT;
			placePiece(rc, p, off, size, label, null, cx, cy, cz, k, 0, true);
			top = mark;
			return rc.primFrame[p];
		}
		int fi = index(label);
		rc.primFrame[p] = fi;
		parts++;
		return fi;
	}

	/**
	 * Places the polygon {@code arena[off .. off + len)} (vertex indices) of primitive {@code prim}
	 * in {@code frame}, cutting it at the faces from {@code first} on; {@code (rx, ry, rz)} is the
	 * local point whose side selects the faces, {@code came} the frame it came from.
	 */
	private void placePiece(IplModelRecorder.Recording rc, int prim, int off, int len, IplRouteFrame frame,
							@Nullable IplRouteFrame came, double rx, double ry, double rz, int first, int nest,
							boolean whole) {
		if (nest < MAX_NEST) {
			Boundary[] bs = boundaries(frame);
			boolean polygon = rc.primitiveSize >= 3;
			for (int k = first; k < bs.length; k++) {
				Boundary b = bs[k];
				if (b.to == came || !b.applies(rx, ry, rz)) continue;
				int cls = classify(rc.pos, arena, off, len, b.region);
				if (cls == OUTSIDE) continue;
				if (cls == INSIDE) {
					centre(rc.pos, off, len);
					double qx = cenX, qy = cenY, qz = cenZ;
					if (!overShape(b, qx, qy, qz)) continue;
					placePiece(rc, prim, off, len, b.to, frame, qx, qy, qz, 0, nest + 1, whole);
					return;
				}
				// Across the region: peel off what lies outside each half-space, keep the inside.
				int mark = top;
				int restOff = off, restLen = len;
				boolean rest = true;
				for (double[] h : b.region) {
					double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
					for (int i = 0; i < restLen; i++) {
						double dv = distance(rc.pos, arena[restOff + i], h);
						if (dv < min) min = dv;
						if (dv > max) max = dv;
					}
					if (min >= -ON_PLANE) continue;
					if (max <= ON_PLANE) {
						placePiece(rc, prim, restOff, restLen, frame, came, rx, ry, rz, k + 1, nest, false);
						rest = false;
						break;
					}
					int outOff = clip(rc, restOff, restLen, h, -1.0, polygon);
					int outLen = clipped;
					if (valid(outLen, polygon)) {
						placePiece(rc, prim, outOff, outLen, frame, came, rx, ry, rz, k + 1, nest, false);
					}
					restOff = clip(rc, restOff, restLen, h, 1.0, polygon);
					restLen = clipped;
					if (!valid(restLen, polygon)) {
						rest = false;
						break;
					}
				}
				if (rest) {
					centre(rc.pos, restOff, restLen);
					double qx = cenX, qy = cenY, qz = cenZ;
					if (overShape(b, qx, qy, qz)) {
						placePiece(rc, prim, restOff, restLen, b.to, frame, qx, qy, qz, 0, nest + 1, false);
					} else {
						placePiece(rc, prim, restOff, restLen, frame, came, rx, ry, rz, k + 1, nest, false);
					}
				}
				top = mark;
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
		int need = rc.piecesSize + 3 + len;
		if (rc.pieces.length < need) rc.pieces = Arrays.copyOf(rc.pieces, Math.max(need, rc.pieces.length * 2));
		int[] out = rc.pieces;
		int at = rc.piecesSize;
		out[at] = prim;
		out[at + 1] = fi;
		out[at + 2] = len;
		System.arraycopy(arena, off, out, at + 3, len);
		rc.piecesSize = need;
	}

	private boolean overShape(Boundary b, double lx, double ly, double lz) {
		return b.rect.opening.rectangular || b.rect.over(ox + lx, oy + ly, oz + lz);
	}

	/** Centre of the arena polygon into {@link #cenX}, {@link #cenY}, {@link #cenZ}. */
	private static void centre(float[] pos, int off, int len) {
		double x = 0.0, y = 0.0, z = 0.0;
		int[] idx = arena;
		for (int i = 0; i < len; i++) {
			int v = idx[off + i] * 3;
			x += pos[v];
			y += pos[v + 1];
			z += pos[v + 2];
		}
		double inv = 1.0 / len;
		cenX = x * inv;
		cenY = y * inv;
		cenZ = z * inv;
	}

	/**
	 * Where the polygon lies relative to a convex region: wholly outside one half-space, wholly
	 * inside all, or across. {@code idx == null}: vertices {@code off .. off + n}.
	 */
	private static int classify(float[] pos, @Nullable int[] idx, int off, int n, double[][] region) {
		boolean across = false;
		for (double[] h : region) {
			double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < n; i++) {
				double d = distance(pos, idx == null ? off + i : idx[off + i], h);
				if (d < min) min = d;
				if (d > max) max = d;
			}
			if (max <= ON_PLANE) return OUTSIDE;
			if (min < -ON_PLANE) across = true;
		}
		return across ? ACROSS : INSIDE;
	}

	private static boolean valid(int len, boolean polygon) {
		return len >= (polygon ? 3 : 2);
	}

	/** The part of the arena polygon on the {@code sign} side of {@code plane}, written to the arena. */
	private static int clip(IplModelRecorder.Recording rc, int inOff, int n, double[] plane, double sign, boolean polygon) {
		int outOff = alloc(n + 2);
		int m = 0;
		int edges = polygon ? n : 1;
		for (int i = 0; i < edges; i++) {
			int p = arena[inOff + i], q = arena[inOff + (i + 1) % n];
			double dp = sign * distance(rc.pos, p, plane), dq = sign * distance(rc.pos, q, plane);
			boolean pin = dp >= 0.0, qin = dq >= 0.0;
			if (pin) arena[outOff + m++] = p;
			if (pin != qin) {
				int v = rc.lerp(p, q, dp / (dp - dq));
				arena[outOff + m++] = v;
			}
			if (!polygon && qin) arena[outOff + m++] = q;
		}
		clipped = m;
		return outOff;
	}

	private static int alloc(int n) {
		int at = top;
		if (at + n > arena.length) arena = Arrays.copyOf(arena, Math.max(at + n, arena.length * 2));
		top = at + n;
		return at;
	}

	private static double distance(float[] pos, int v, double[] p) {
		int i = v * 3;
		return p[0] * pos[i] + p[1] * pos[i + 1] + p[2] * pos[i + 2] + p[3];
	}
}
