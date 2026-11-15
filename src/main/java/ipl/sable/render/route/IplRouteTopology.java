package ipl.sable.render.route;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * Which primitives of a recorded model hang together: the tether the route walk follows from the
 * holder ({@link IplRouteSolver}).
 *
 * <p>Two primitives touch when their bounding spheres overlap (each radius capped at half a grid
 * cell, so a long quad still finds the neighbours around its centre and a dense cluster costs a
 * bounded scan). Pieces that touch nothing (separate parts of one model, a rope drawn as loose
 * segments) are joined to their nearest piece through ever coarser grids (Boruvka rounds), and
 * whatever is still apart joins the largest piece at its nearest point. The graph is therefore
 * connected: every primitive has a path to the holder.
 *
 * <p>Touching is a property of the model, not of a frame: the graph is kept while the layout of the
 * recording (render types and primitive counts) is unchanged and rebuilt when it changes, or when
 * the walk finds the model torn apart (edges stretched far beyond their built length). A build is
 * near linear in the primitive count; a frame only walks the compiled adjacency. Render thread only.
 */
final class IplRouteTopology {
	private static final int MAX_DEGREE = 16;
	/** Candidates one primitive examines in the touch pass (dense clusters). */
	private static final int MAX_EXAMINED = 256;
	private static final int BRIDGE_ROUNDS = 6;
	private static final double BRIDGE_GROWTH = 4.0;
	private static final int BRIDGE_EXAMINED = 512;
	/** Distance evaluations allowed for joining what the bridge rounds left apart. */
	private static final long FINAL_BRIDGE_WORK = 4_000_000L;

	// Scratch shared by every build (render thread only).
	private static final Long2IntOpenHashMap HEAD = new Long2IntOpenHashMap();
	private static int[] next = new int[0];
	private static int[] parent = new int[0];
	private static int[] degree = new int[0];
	private static int[] edges = new int[0];
	private static int edgeCount;
	private static double[] bestD = new double[0];
	private static int[] bestI = new int[0];
	private static int[] bestJ = new int[0];
	private static double baseX, baseY, baseZ;

	static {
		HEAD.defaultReturnValue(-1);
	}

	/** Primitive count the graph was built for; -1 = none. */
	int units = -1;
	private boolean dirty = true;
	/** Compiled adjacency: neighbours of {@code i} are {@code adj[start[i] .. start[i + 1])}. */
	int[] start = new int[1];
	int[] adj = new int[0];
	/** Length of each adjacency entry when it was built. */
	float[] length = new float[0];

	boolean valid(int n) {
		return !dirty && units == n;
	}

	void invalidate() {
		dirty = true;
	}

	/** Builds the graph of {@code n} primitives: centres {@code c} (xyz), radii {@code r}. */
	void build(double[] c, float[] r, int n) {
		units = n;
		dirty = false;
		edgeCount = 0;
		scratch(n);
		for (int i = 0; i < n; i++) {
			parent[i] = i;
			degree[i] = 0;
		}
		if (n > 1) {
			baseX = c[0];
			baseY = c[1];
			baseZ = c[2];
			double sum = 0.0;
			for (int i = 0; i < n; i++) sum += Math.min(r[i], 1.0f);
			double s = Math.max(1.0 / 32.0, Math.min(2.0, 2.0 * sum / n));
			double slack = Math.max(0.02, 0.05 * s);
			double cell = s + slack;
			touch(c, r, n, s * 0.5, slack, cell);
			int comps = 0;
			for (int i = 0; i < n; i++) if (find(i) == i) comps++;
			for (int round = 0; comps > 1 && round < BRIDGE_ROUNDS; round++) {
				cell *= BRIDGE_GROWTH;
				comps -= bridge(c, n, cell);
			}
			if (comps > 1) bridgeRest(c, n);
			HEAD.clear();
		}
		compile(c, n);
	}

	// ------------------------------------------------------------------ passes

	private static void touch(double[] c, float[] r, int n, double cap, double slack, double cell) {
		index(c, n, cell);
		for (int i = 0; i < n; i++) {
			double xi = c[i * 3], yi = c[i * 3 + 1], zi = c[i * 3 + 2];
			double ri = Math.min(r[i], cap);
			int cx = cell(xi - baseX, cell), cy = cell(yi - baseY, cell), cz = cell(zi - baseZ, cell);
			int examined = 0;
			scan:
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						// Chains run from the highest index down: stop at the first j <= i.
						for (int j = HEAD.get(key(cx + dx, cy + dy, cz + dz)); j > i; j = next[j]) {
							if (degree[i] >= MAX_DEGREE || ++examined > MAX_EXAMINED) break scan;
							if (degree[j] >= MAX_DEGREE) continue;
							double ex = c[j * 3] - xi, ey = c[j * 3 + 1] - yi, ez = c[j * 3 + 2] - zi;
							double lim = ri + Math.min(r[j], cap) + slack;
							if (ex * ex + ey * ey + ez * ez <= lim * lim) {
								edge(i, j);
								union(i, j);
							}
						}
					}
				}
			}
		}
	}

	/** One Boruvka round: every piece joins its nearest other piece within a cell. */
	private static int bridge(double[] c, int n, double cell) {
		index(c, n, cell);
		for (int i = 0; i < n; i++) {
			bestD[i] = Double.POSITIVE_INFINITY;
			bestI[i] = -1;
		}
		for (int i = 0; i < n; i++) {
			int ci = find(i);
			double xi = c[i * 3], yi = c[i * 3 + 1], zi = c[i * 3 + 2];
			int cx = cell(xi - baseX, cell), cy = cell(yi - baseY, cell), cz = cell(zi - baseZ, cell);
			int examined = 0;
			scan:
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						for (int j = HEAD.get(key(cx + dx, cy + dy, cz + dz)); j >= 0; j = next[j]) {
							if (++examined > BRIDGE_EXAMINED) break scan;
							if (find(j) == ci) continue;
							double ex = c[j * 3] - xi, ey = c[j * 3 + 1] - yi, ez = c[j * 3 + 2] - zi;
							double d = ex * ex + ey * ey + ez * ez;
							if (d < bestD[ci]) {
								bestD[ci] = d;
								bestI[ci] = i;
								bestJ[ci] = j;
							}
						}
					}
				}
			}
		}
		int joined = 0;
		for (int k = 0; k < n; k++) {
			if (bestI[k] < 0) continue;
			int a = find(bestI[k]), b = find(bestJ[k]);
			if (a == b) continue;
			union(a, b);
			edge(bestI[k], bestJ[k]);
			joined++;
		}
		return joined;
	}

	/** What the rounds left apart joins the largest piece at its nearest point (bounded work). */
	private static void bridgeRest(double[] c, int n) {
		int[] size = bestI;
		Arrays.fill(size, 0, n, 0);
		int largest = -1;
		for (int i = 0; i < n; i++) {
			int root = find(i);
			if (++size[root] > (largest < 0 ? 0 : size[largest])) largest = root;
		}
		int[] members = bestJ;
		int count = 0;
		for (int i = 0; i < n; i++) if (find(i) == largest) members[count++] = i;
		long work = 0;
		for (int i = 0; i < n && work < FINAL_BRIDGE_WORK; i++) {
			if (find(i) != i || i == largest) continue;
			double xi = c[i * 3], yi = c[i * 3 + 1], zi = c[i * 3 + 2];
			int best = -1;
			double bestDistance = Double.POSITIVE_INFINITY;
			for (int m = 0; m < count; m++) {
				int j = members[m];
				double ex = c[j * 3] - xi, ey = c[j * 3 + 1] - yi, ez = c[j * 3 + 2] - zi;
				double d = ex * ex + ey * ey + ez * ez;
				if (d < bestDistance) {
					bestDistance = d;
					best = j;
				}
			}
			work += count;
			if (best >= 0) edge(i, best);
		}
	}

	private void compile(double[] c, int n) {
		if (start.length < n + 1) start = new int[Math.max(n + 1, start.length * 2)];
		Arrays.fill(start, 0, n + 1, 0);
		for (int e = 0; e < edgeCount; e++) {
			start[edges[e * 2] + 1]++;
			start[edges[e * 2 + 1] + 1]++;
		}
		for (int i = 0; i < n; i++) start[i + 1] += start[i];
		int total = edgeCount * 2;
		if (adj.length < total) {
			adj = new int[Math.max(total, adj.length * 2)];
			length = new float[adj.length];
		}
		int[] cursor = degree;
		System.arraycopy(start, 0, cursor, 0, n);
		for (int e = 0; e < edgeCount; e++) {
			int a = edges[e * 2], b = edges[e * 2 + 1];
			float d = (float) Math.sqrt(distanceSq(c, a, b));
			int ka = cursor[a]++, kb = cursor[b]++;
			adj[ka] = b;
			length[ka] = d;
			adj[kb] = a;
			length[kb] = d;
		}
	}

	// ------------------------------------------------------------------ helpers

	private static double distanceSq(double[] c, int a, int b) {
		double ex = c[a * 3] - c[b * 3], ey = c[a * 3 + 1] - c[b * 3 + 1], ez = c[a * 3 + 2] - c[b * 3 + 2];
		return ex * ex + ey * ey + ez * ez;
	}

	private static void index(double[] c, int n, double cell) {
		HEAD.clear();
		for (int i = 0; i < n; i++) {
			long k = key(cell(c[i * 3] - baseX, cell), cell(c[i * 3 + 1] - baseY, cell), cell(c[i * 3 + 2] - baseZ, cell));
			next[i] = HEAD.put(k, i);
		}
	}

	private static void edge(int a, int b) {
		int need = edgeCount * 2 + 2;
		if (edges.length < need) edges = Arrays.copyOf(edges, Math.max(need, edges.length * 2));
		edges[edgeCount * 2] = a;
		edges[edgeCount * 2 + 1] = b;
		edgeCount++;
		degree[a]++;
		degree[b]++;
	}

	private static int find(int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int a, int b) {
		int ra = find(a), rb = find(b);
		if (ra != rb) parent[ra] = rb;
	}

	private static void scratch(int n) {
		if (next.length >= n) return;
		int size = Math.max(n, next.length * 2);
		next = new int[size];
		parent = new int[size];
		degree = new int[size];
		bestD = new double[size];
		bestI = new int[size];
		bestJ = new int[size];
	}

	private static int cell(double v, double cell) {
		return (int) Math.floor(v / cell);
	}

	private static long key(int x, int y, int z) {
		return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | (long) z & 0x1FFFFFL;
	}
}
