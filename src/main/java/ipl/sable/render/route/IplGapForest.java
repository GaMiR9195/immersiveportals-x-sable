package ipl.sable.render.route;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Joins the separate pieces of a model (chain links, the boxes of rope segments, the cubes of a
 * cogwheel) nearest-first: a minimum spanning tree over sampled vertices, between pieces. Close
 * pairs come from a uniform grid (near-linear); pieces farther apart than one cell are joined by
 * a direct search over what is left, which a model rarely has. The joins are returned in the
 * order a walk from the starting piece meets them.
 */
final class IplGapForest {

	/** Grid cell edge: vertex pairs closer than this are found through the grid. */
	private static final double CELL = 1.0;

	/** A join from a sample of an already reached piece to a sample of a new piece. */
	record Join(int from, int to) {}

	private final double[] x, y, z;
	private final int[] piece;
	private final int pieces;
	private final int[] group;

	private IplGapForest(double[] x, double[] y, double[] z, int[] piece, int pieces) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.piece = piece;
		this.pieces = pieces;
		this.group = new int[pieces];
		for (int i = 0; i < pieces; i++) group[i] = i;
	}

	/**
	 * @param piece  piece index ({@code 0..pieces-1}) of every sample
	 * @param start  the sample the walk starts at
	 */
	static List<Join> joins(double[] x, double[] y, double[] z, int[] piece, int pieces, int start) {
		if (pieces <= 1) return List.of();
		IplGapForest forest = new IplGapForest(x, y, z, piece, pieces);
		List<int[]> tree = forest.gridTree();
		forest.completeTree(tree);
		return forest.walk(tree, start);
	}

	/** Kruskal over the sample pairs closer than {@link #CELL}. */
	private List<int[]> gridTree() {
		Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>();
		for (int i = 0; i < x.length; i++) {
			grid.computeIfAbsent(cell(cx(i), cy(i), cz(i)), k -> new IntArrayList(4)).add(i);
		}
		IntArrayList a = new IntArrayList(), b = new IntArrayList();
		double[] distances = new double[16];
		int edges = 0;
		for (int i = 0; i < x.length; i++) {
			int gx = cx(i), gy = cy(i), gz = cz(i);
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				IntArrayList cellSamples = grid.get(cell(gx + dx, gy + dy, gz + dz));
				if (cellSamples == null) continue;
				for (int k = 0; k < cellSamples.size(); k++) {
					int j = cellSamples.getInt(k);
					if (j <= i || piece[j] == piece[i]) continue;
					double d = distanceSquared(i, j);
					if (d > CELL * CELL) continue;
					if (edges == distances.length) distances = Arrays.copyOf(distances, edges * 2);
					distances[edges++] = d;
					a.add(i);
					b.add(j);
				}
			}
		}
		int[] order = new int[edges];
		for (int e = 0; e < edges; e++) order[e] = e;
		double[] dist = distances;
		IntArrays.quickSort(order, 0, edges, (p, q) -> Double.compare(dist[p], dist[q]));
		List<int[]> tree = new ArrayList<>(pieces - 1);
		for (int e = 0; e < edges; e++) {
			int i = a.getInt(order[e]), j = b.getInt(order[e]);
			if (union(piece[i], piece[j])) tree.add(new int[]{i, j});
			if (tree.size() == pieces - 1) break;
		}
		return tree;
	}

	/** Joins the groups the grid left apart, nearest pair first (Prim over groups). */
	private void completeTree(List<int[]> tree) {
		while (tree.size() < pieces - 1) {
			int home = find(piece[0]);
			double best = Double.POSITIVE_INFINITY;
			int bi = -1, bj = -1;
			for (int i = 0; i < x.length; i++) {
				if (find(piece[i]) != home) continue;
				for (int j = 0; j < x.length; j++) {
					if (find(piece[j]) == home) continue;
					double d = distanceSquared(i, j);
					if (d < best) { best = d; bi = i; bj = j; }
				}
			}
			if (bi < 0) return;
			union(piece[bi], piece[bj]);
			tree.add(new int[]{bi, bj});
		}
	}

	/** The tree's joins oriented away from the piece of {@code start}, in walk order. */
	private List<Join> walk(List<int[]> tree, int start) {
		List<List<int[]>> adjacent = new ArrayList<>(pieces);
		for (int p = 0; p < pieces; p++) adjacent.add(new ArrayList<>(2));
		for (int[] e : tree) {
			adjacent.get(piece[e[0]]).add(e);
			adjacent.get(piece[e[1]]).add(e);
		}
		boolean[] reached = new boolean[pieces];
		List<Join> out = new ArrayList<>(tree.size());
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		reached[piece[start]] = true;
		queue.add(piece[start]);
		while (!queue.isEmpty()) {
			int at = queue.poll();
			for (int[] e : adjacent.get(at)) {
				int from = piece[e[0]] == at ? e[0] : e[1];
				int to = from == e[0] ? e[1] : e[0];
				if (reached[piece[to]]) continue;
				reached[piece[to]] = true;
				out.add(new Join(from, to));
				queue.add(piece[to]);
			}
		}
		return out;
	}

	private boolean union(int p, int q) {
		int rp = find(p), rq = find(q);
		if (rp == rq) return false;
		group[rq] = rp;
		return true;
	}

	private int find(int p) {
		while (group[p] != p) {
			group[p] = group[group[p]];
			p = group[p];
		}
		return p;
	}

	private double distanceSquared(int i, int j) {
		double dx = x[i] - x[j], dy = y[i] - y[j], dz = z[i] - z[j];
		return dx * dx + dy * dy + dz * dz;
	}

	private int cx(int i) { return (int) Math.floor(x[i] / CELL); }
	private int cy(int i) { return (int) Math.floor(y[i] / CELL); }
	private int cz(int i) { return (int) Math.floor(z[i] / CELL); }

	private static long cell(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
