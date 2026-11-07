package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplStraddleSessionStore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The pieces of one sub-level (README, section 2): the native piece and one node per live
 * straddle session (root = the body crossing a portal of its own level, child = a piece
 * crossing a portal of its parent's destination level), together with the {@link IplPieceMap
 * labels} that say which material belongs to which node.
 *
 * <p>Every interaction asks the same question -- "which piece is this plot point in?" -- and
 * gets the answer the physics uses: the label of the voxel, cut by the aperture plane only
 * inside the thin zone around an aperture ({@link IplPieceMap#classify}). Material that passed
 * a portal plane beside its frame keeps its piece; there is no half-space or tube anywhere.
 *
 * <p>Server trees use the tracker's map and edges. Client trees use the mirrored labels and
 * rebuild the edges at the logical pose against the mirrored label-time rectangles.
 * Cached per thread and tick; server cache hits also require the latest sampled edge array,
 * because several material/geometry samples now occur within one game tick.
 */
public final class IplProjectionTree {

    private static final IplPieceMap NO_PIECES = new IplPieceMap();
    private static final IplPieceMap.Edge[] NO_EDGES = new IplPieceMap.Edge[0];
    private static final IplProjectionTree EMPTY =
        new IplProjectionTree(List.of(), List.of(), new Node[0], NO_PIECES, NO_EDGES, null);

    /** One session: the piece of the body that went through {@link #portal}. */
    public static final class Node {
        /** Piece id (label) of this session, {@code 1..255}. */
        public final int id;
        public final String path;
        public final Portal portal;
        @Nullable public final Node parent;
        /** This portal's own isometry (source frame of the portal -> its destination). */
        public final IplStraddlePoseMap.StraddleMapping local;
        /** Composite isometry: real-body world -> this node's destination world. */
        public final IplStraddlePoseMap.StraddleMapping mapping;
        public final List<Node> children = new ArrayList<>(1);
        public final int depth;

        Node(int id, String path, Portal portal, @Nullable Node parent) {
            this.id = id;
            this.path = path;
            this.portal = portal;
            this.parent = parent;
            this.local = IplStraddlePoseMap.StraddleMapping.of(portal);
            this.mapping = parent == null ? local : parent.mapping.then(local);
            this.depth = parent == null ? 1 : parent.depth + 1;
        }

        public ResourceKey<Level> destDim() {
            return portal.getDestDim();
        }

        public boolean isChained() {
            return parent != null;
        }

        /** Piece id of the parent ({@link IplPieceMap#NATIVE} for a root). */
        public int parentId() {
            return parent == null ? IplPieceMap.NATIVE : parent.id;
        }

        /** Portals of the path, root first. */
        public List<Portal> pathPortals() {
            List<Portal> out = new ArrayList<>(depth);
            for (Node n = this; n != null; n = n.parent) out.add(0, n.portal);
            return out;
        }

        /** The root session of this node's path. */
        public Node root() {
            Node n = this;
            while (n.parent != null) n = n.parent;
            return n;
        }
    }

    private final List<Node> roots;
    private final List<Node> nodes;
    private final Node[] byId;
    private final IplPieceMap pieces;
    private final IplPieceMap.Edge[] edges;
    @Nullable private final Pose3dc pose;

    private IplProjectionTree(List<Node> roots, List<Node> nodes, Node[] byId, IplPieceMap pieces,
                              IplPieceMap.Edge[] edges, @Nullable Pose3dc pose) {
        this.roots = roots;
        this.nodes = nodes;
        this.byId = byId;
        this.pieces = pieces;
        this.edges = edges;
        this.pose = pose;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public List<Node> roots() {
        return roots;
    }

    /** Every node, parents before children. */
    public List<Node> nodes() {
        return nodes;
    }

    /** The node of piece {@code id}; null for the native piece and unknown ids. */
    @Nullable
    public Node node(int id) {
        return id > 0 && id < byId.length ? byId[id] : null;
    }

    public IplPieceMap pieces() {
        return pieces;
    }

    public IplPieceMap.Edge[] edges() {
        return edges;
    }

    /** The piece of a plot point (see {@link IplPieceMap#classify}); unknown labels are native. */
    public int pieceOfPlot(double x, double y, double z) {
        int piece = pieces.classify(edges, x, y, z);
        return piece == IplPieceMap.NATIVE || node(piece) != null ? piece : IplPieceMap.NATIVE;
    }

    /** Label of a plot voxel; labels without a live node count as native (as in {@link #pieceOfPlot}). */
    public int labelOf(int x, int y, int z) {
        int label = pieces.label(x, y, z);
        return label == IplPieceMap.NATIVE || node(label) != null ? label : IplPieceMap.NATIVE;
    }

    /** The node a REAL-frame world point of the body belongs to; null = the native piece. */
    @Nullable
    public Node pieceAt(Vec3 realWorld) {
        if (isEmpty() || pose == null) return null;
        Vec3 plot = pose.transformPositionInverse(realWorld);
        return node(pieceOfPlot(plot.x, plot.y, plot.z));
    }

    /** Conservative candidate gate; per-point masks decide actual material presence. */
    public boolean hasMaterial(Node node) {
        // Empty labels do not mean empty geometry: a newly armed aperture can already
        // cut the front half of an unlabelled voxel. Per-point/box classification below
        // decides whether material actually exists in this image.
        return node != null && node(node.id) != null;
    }

    /** The edge whose aperture separates {@code node} from its parent; null if unknown. */
    @Nullable
    public IplPieceMap.Edge edgeInto(Node node) {
        for (IplPieceMap.Edge e : edges) {
            if (e.child == node.id) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ build

    /** One session as the tree sees it: path, parent path ({@code ""} for roots), portal, piece id. */
    public record Entry(String path, String via, Portal portal, int node) {}

    /**
     * Builds the tree from session entries. {@code edges} null = rebuild them from the portals
     * at {@code pose} (client).
     */
    public static IplProjectionTree build(SubLevel sub, List<Entry> entries, IplPieceMap pieces,
                                          IplPieceMap.Edge[] edges) {
        if (entries.isEmpty()) return EMPTY;
        Pose3dc pose = sub.logicalPose();
        List<Entry> ordered = new ArrayList<>(entries);
        // Parents strictly before children, whatever order the session map iterated in.
        ordered.sort(Comparator.<Entry>comparingInt(e -> pathDepth(e.path())).thenComparing(Entry::path));
        Map<String, Node> byPath = new java.util.HashMap<>();
        Node[] byId = new Node[IplPieceMap.MAX_PIECE + 1];
        List<Node> roots = new ArrayList<>(2);
        List<Node> all = new ArrayList<>(entries.size());
        for (Entry e : ordered) {
            if (e.portal() == null || byPath.containsKey(e.path())) continue;
            if (e.node() <= 0 || e.node() > IplPieceMap.MAX_PIECE || byId[e.node()] != null) continue;
            if (Math.abs(e.portal().getScaling() - 1.0) > 1e-9) continue;
            Node parent = e.via().isEmpty() ? null : byPath.get(e.via());
            if (!e.via().isEmpty() && parent == null) continue; // orphan chain this tick
            Node node = new Node(e.node(), e.path(), e.portal(), parent);
            byPath.put(e.path(), node);
            byId[node.id] = node;
            all.add(node);
            if (parent == null) roots.add(node);
            else parent.children.add(node);
        }
        if (all.isEmpty()) return EMPTY;
        IplPieceMap.Edge[] resolved = edges != null ? edges : clientEdges(all, pose, pieces);
        return new IplProjectionTree(List.copyOf(roots), List.copyOf(all), byId, pieces, resolved, pose);
    }

    /** Edges of mirrored sessions: each aperture in the plot frame of its parent piece's image. */
    private static IplPieceMap.Edge[] clientEdges(List<Node> nodes, Pose3dc pose, IplPieceMap pieces) {
        IplPieceMap.Edge[] out = new IplPieceMap.Edge[nodes.size()];
        for (int i = 0; i < out.length; i++) {
            Node n = nodes.get(i);
            Pose3dc parentPose = n.parent == null ? pose : n.parent.mapping.mapPose(pose);
            IplPieceMap.Aperture plot = IplPieceMap.Aperture.of(n.portal).toPlot(parentPose);
            out[i] = new IplPieceMap.Edge(n.parentId(), n.id, plot, pieces.reference(n.parentId(), n.id, plot));
        }
        return out;
    }

    private static int pathDepth(String path) {
        int depth = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '>') depth++;
        return depth;
    }

    // ------------------------------------------------------------------ lookup + cache

    private static final class Cache {
        long stamp = Long.MIN_VALUE;
        long generation = Long.MIN_VALUE;
        final Map<SubLevel, IplProjectionTree> trees = new IdentityHashMap<>();
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);
    private static volatile long generation;

    /** Session sets or labels changed; every cached tree is stale. */
    public static void invalidate() {
        generation++;
    }

    /** Monotonic stamp of the session-set generation (cache keys of derived data). */
    public static long generation() {
        return generation;
    }

    /** The projection tree of {@code sub} (empty when it straddles nothing). */
    public static IplProjectionTree of(@Nullable SubLevel sub) {
        if (sub == null || sub.isRemoved()) return EMPTY;
        Level level = sub.getLevel();
        if (level == null) return EMPTY;
        boolean client = level.isClientSide();
        if (client ? IplStraddleSessionStore.isIdle() : IplAtlasStraddleSession.isIdle()) return EMPTY;
        Cache cache = CACHE.get();
        long stamp = client
            ? IplClientProjections.clientStamp()
            : level instanceof ServerLevel server ? server.getServer().getTickCount() : 0L;
        long gen = generation;
        if (cache.stamp != stamp || cache.generation != gen) {
            cache.trees.clear();
            cache.stamp = stamp;
            cache.generation = gen;
        }
        IplPieceMap serverPieces = null;
        IplPieceMap.Edge[] serverEdges = NO_EDGES;
        if (!client) {
            serverPieces = IplPieceTracker.mapOf(sub.getUniqueId());
            if (serverPieces == null) serverPieces = NO_PIECES;
            else serverEdges = serverPieces.edges();
        }
        IplProjectionTree tree = cache.trees.get(sub);
        if (!client && tree != null && (tree.pieces != serverPieces || tree.edges != serverEdges)) {
            // Do not let a once-per-tick tree retain the preceding physics substep's planes.
            // This is local invalidation: no cross-thread/client render generation churn.
            tree = null;
        }
        if (tree == null) {
            if (client) {
                tree = build(sub, IplClientProjections.treeEntries(sub),
                    IplStraddleSessionStore.pieces(sub.getUniqueId()), null);
            } else {
                tree = build(sub, IplAtlasStraddleSession.treeEntries(sub), serverPieces, serverEdges);
            }
            cache.trees.put(sub, tree);
        }
        return tree;
    }

    // ------------------------------------------------------------------ frame queries

    /**
     * The pose a body point is physically at as seen from {@code ctx}, given its REAL world
     * position: its piece's mapping applied to {@code realPose} when that piece lives in
     * {@code ctx}, {@code realPose} for the native piece in the native level, otherwise null.
     */
    @Nullable
    public static Pose3dc piecePoseIn(SubLevel sub, Level ctx, Pose3dc realPose, Vec3 realWorld) {
        Node piece = of(sub).pieceAt(realWorld);
        if (piece == null) return sub.getLevel() == ctx ? realPose : null;
        return piece.destDim() == ctx.dimension() ? piece.mapping.mapPose(realPose) : null;
    }

    /**
     * Where a REAL-frame world point of {@code sub} physically is as seen from {@code ctx}: its
     * piece's mapped position when that piece lives in {@code ctx}; null for the native piece in
     * the native level (unchanged). When the piece is elsewhere, the first image into
     * {@code ctx}, so distances stay finite and continuous.
     */
    @Nullable
    public static Vec3 worldPointIn(SubLevel sub, Level ctx, Vec3 realWorld) {
        IplProjectionTree tree = of(sub);
        if (tree.isEmpty()) return null;
        Node piece = tree.pieceAt(realWorld);
        if (piece != null && piece.destDim() == ctx.dimension()) return piece.mapping.mapPoint(realWorld);
        if (sub.getLevel() == ctx) return null;
        for (Node n : tree.nodes()) {
            if (n.destDim() == ctx.dimension()) return n.mapping.mapPoint(realWorld);
        }
        return null;
    }

    /**
     * Every frame through which {@code sub} is present in {@code ctx}: the native frame (node
     * null) when native, plus each node whose image lives there and holds material.
     */
    public static void forEachFrameIn(SubLevel sub, Level ctx, Consumer<Node> visitor) {
        IplProjectionTree tree = of(sub);
        if (sub.getLevel() == ctx) visitor.accept(null);
        for (Node n : tree.nodes()) {
            if (n.destDim() == ctx.dimension() && tree.hasMaterial(n)) visitor.accept(n);
        }
    }
}
