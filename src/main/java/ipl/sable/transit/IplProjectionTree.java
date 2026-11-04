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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * PROJECTION TREE of one sub-level: every live straddle session (root = the real body
 * crossing a portal of its own level, child = an image crossing a portal of its
 * destination level) as one node, with the exact region geometry the native contact clip
 * uses.
 *
 * <p>Одна истина для всех взаимодействий (пик, дистанция досягаемости, контур, нажимные
 * плиты, сущности, отсечение рендера): дерево сессий и та же геометрия призм прохода, что и в
 * физике (natives: KEEP = пересечение призм пути, EXCLUDE = призмы дочерних порталов). Одной
 * плоскости первого портала для этого мало: с несколькими порталами и цепочками (выход из
 * 2-го портала и дальше) она даёт неверный кадр, а бесконечная плоскость забирала бы и то,
 * что прошло мимо рамки.
 *
 * <p>A plot point belongs to exactly ONE piece: the native piece (inside no root prism) or
 * the deepest node whose prism chain it went through. Region tests reproduce
 * {@code IplAtlasStraddleSession}'s regions ({@link PortalPrism}: same anchor, skin, lateral
 * margin and depth bound), so interaction, collision images and physics always agree on
 * where a block physically is. Built from server sessions or from the client session mirror;
 * cached per thread and tick, invalidated whenever a session set changes.
 *
 * <p>BRANCHES AND SIBLING PRIORITY. Several roots of one body, or several children of one
 * node, are branches: different parts of the body went through different portals, each part
 * owns the material inside its own prism. Siblings can still claim the same space (adjacent
 * apertures share their lateral margin, a portal standing beyond another one's plane lies
 * inside that one's prism). Contested space goes to the sibling that comes first in
 * {@link #build}'s order -- UPSTREAM portals before the ones beyond their plane
 * ({@link PortalOrder}), then path order -- and the physics regions apply the same order
 * ({@code IplAtlasStraddleSession.outranks}): material that went through a portal is never
 * claimed by a portal further along its old (native) path.
 */
public final class IplProjectionTree {

    private static final IplProjectionTree EMPTY = new IplProjectionTree(null, List.of(), List.of());

    /** One session: the body (or an image of it) crossing {@link #portal}. */
    public static final class Node {
        public final String path;
        public final Portal portal;
        @Nullable public final Node parent;
        /** This portal's own isometry (source frame of the portal -> its destination). */
        public final IplStraddlePoseMap.StraddleMapping local;
        /** Composite isometry: real-body world -> this node's destination world. */
        public final IplStraddlePoseMap.StraddleMapping mapping;
        public final List<Node> children = new ArrayList<>(1);
        /** Through region of {@link #portal} in this node's SOURCE frame (the physics shape). */
        public final PortalPrism prism;
        final int depth;

        Node(String path, Portal portal, @Nullable Node parent, PortalPrism prism) {
            this.path = path;
            this.portal = portal;
            this.parent = parent;
            this.local = IplStraddlePoseMap.StraddleMapping.of(portal);
            this.mapping = parent == null ? local : parent.mapping.then(local);
            this.prism = prism;
            this.depth = parent == null ? 1 : parent.depth + 1;
        }

        public ResourceKey<Level> destDim() {
            return portal.getDestDim();
        }

        public boolean isChained() {
            return parent != null;
        }

        /** Portals of the path, root first. */
        public List<Portal> pathPortals() {
            List<Portal> out = new ArrayList<>(depth);
            for (Node n = this; n != null; n = n.parent) out.add(0, n.portal);
            return out;
        }

        /** Point in this node's SOURCE frame lies in its through prism. */
        public boolean beyondContains(Vec3 w) {
            return prism.contains(w);
        }

        /**
         * Ray-parameter interval [t0, t1] where {@code from + t*dir} (SOURCE frame of this
         * node, {@code dir} unit) is inside the through prism; null when never.
         */
        @Nullable
        public double[] beyondInterval(Vec3 from, Vec3 dir) {
            return prism.interval(from, dir);
        }

        /** The root session of this node's path. */
        public Node root() {
            Node n = this;
            while (n.parent != null) n = n.parent;
            return n;
        }

        /**
         * Ray interval (DESTINATION frame) inside the KEEP set of this node: the
         * intersection of every ancestor prism mapped forward. Convex, so one interval.
         */
        @Nullable
        public double[] keepInterval(Vec3 from, Vec3 dir) {
            double t0 = Double.NEGATIVE_INFINITY, t1 = Double.POSITIVE_INFINITY;
            Vec3 f = from, d = dir;
            for (Node n = this; n != null; n = n.parent) {
                f = n.local.unmapPoint(f);
                d = n.local.unmapVec(d);
                double[] iv = n.beyondInterval(f, d);
                if (iv == null) return null;
                t0 = Math.max(t0, iv[0]);
                t1 = Math.min(t1, iv[1]);
                if (t0 > t1) return null;
            }
            return new double[]{t0, t1};
        }
    }

    @Nullable private final SubLevel sub;
    private final List<Node> roots;
    private final List<Node> all;

    private IplProjectionTree(@Nullable SubLevel sub, List<Node> roots, List<Node> all) {
        this.sub = sub;
        this.roots = roots;
        this.all = all;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public List<Node> roots() {
        return roots;
    }

    public List<Node> nodes() {
        return all;
    }

    /**
     * The piece a REAL-frame world point (body's own level, real pose) belongs to: null for
     * the native piece, otherwise the deepest node it went through. Contested space between
     * siblings resolves by sibling priority (list order).
     */
    @Nullable
    public Node pieceAt(Vec3 realWorld) {
        for (Node root : roots) {
            if (root.beyondContains(realWorld)) return descend(root, root.local.mapPoint(realWorld));
        }
        return null;
    }

    private static Node descend(Node node, Vec3 wDest) {
        for (Node child : node.children) {
            if (child.beyondContains(wDest)) return descend(child, child.local.mapPoint(wDest));
        }
        return node;
    }

    /** Siblings of {@code node} in priority order (itself included). */
    public List<Node> siblingsOf(Node node) {
        return node.parent == null ? roots : node.parent.children;
    }

    /** Whether {@code node} or an ancestor has a higher-priority sibling (anything to yield to). */
    public boolean yieldsAnything(Node node) {
        for (Node a = node; a != null; a = a.parent) {
            if (siblingsOf(a).get(0) != a) return true;
        }
        return false;
    }

    /**
     * Whether a REAL-frame point inside {@code node}'s regions is nevertheless owned by a
     * higher-priority sibling of {@code node} or of one of its ancestors.
     */
    public boolean yieldsAt(Node node, Vec3 realWorld) {
        for (Node a = node; a != null; a = a.parent) {
            Vec3 source = a.parent == null ? realWorld : a.parent.mapping.mapPoint(realWorld);
            for (Node sibling : siblingsOf(a)) {
                if (sibling == a) break;
                if (sibling.beyondContains(source)) return true;
            }
        }
        return false;
    }

    /**
     * Ray intervals ({@code from}/{@code dir} in {@code node}'s DESTINATION frame) of the
     * space {@code node} yields to higher-priority siblings along its path (see
     * {@link #yieldsAt}); the raycast treats them as holes.
     */
    public List<double[]> yieldedIntervals(Node node, Vec3 from, Vec3 dir) {
        List<double[]> out = null;
        Vec3 f = from;
        Vec3 d = dir;
        for (Node a = node; a != null; a = a.parent) {
            f = a.local.unmapPoint(f);
            d = a.local.unmapVec(d);
            for (Node sibling : siblingsOf(a)) {
                if (sibling == a) break;
                double[] iv = sibling.beyondInterval(f, d);
                if (iv == null) continue;
                if (out == null) out = new ArrayList<>(2);
                out.add(iv);
            }
        }
        return out == null ? List.of() : out;
    }

    // ------------------------------------------------------------------ building

    /**
     * One session description; {@code anchor} = the session's plot-local passage anchor
     * (null: straight tube, see {@link PortalPrism}).
     */
    public record Entry(String path, String via, Portal portal, @Nullable Vec3 anchor) {
        public Entry(String path, String via, Portal portal) {
            this(path, via, portal, null);
        }
    }

    public static IplProjectionTree build(SubLevel sub, List<Entry> entries) {
        return build(sub, entries, sub.logicalPose());
    }

    /**
     * The tree of {@code entries} with the body at {@code pose}: anchors and prism depths follow
     * that pose (the logical pose for physics and interaction, the render pose for drawing).
     */
    public static IplProjectionTree build(SubLevel sub, List<Entry> entries, Pose3dc pose) {
        if (entries.isEmpty()) return EMPTY;
        var box = sub.getPlot().getBoundingBox();
        double dx = box.maxX() - box.minX() + 1.0;
        double dy = box.maxY() - box.minY() + 1.0;
        double dz = box.maxZ() - box.minZ() + 1.0;
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vec3 center = pose.transformPosition(new Vec3(
            (box.minX() + box.maxX() + 1.0) * .5,
            (box.minY() + box.maxY() + 1.0) * .5,
            (box.minZ() + box.maxZ() + 1.0) * .5));
        // Parents strictly before children, whatever order the session map iterated in
        // (a re-opened parent must not orphan its still-live chain for a tick).
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.<Entry>comparingInt(e -> pathDepth(e.path())).thenComparing(Entry::path));
        Map<String, Node> byPath = new HashMap<>();
        List<Node> roots = new ArrayList<>(2);
        for (Entry e : ordered) {
            if (byPath.containsKey(e.path()) || e.portal() == null) continue;
            if (Math.abs(e.portal().getScaling() - 1.0) > 1e-9) continue;
            Node parent = e.via().isEmpty() ? null : byPath.get(e.via());
            if (!e.via().isEmpty() && parent == null) continue; // orphan chain this tick
            Vec3 c = parent == null ? center : parent.mapping.mapPoint(center);
            double normalDistance = Math.abs(c.subtract(e.portal().getOriginPos()).dot(e.portal().getNormal()));
            Vec3 anchor = null;
            if (e.anchor() != null) {
                Vec3 world = pose.transformPosition(e.anchor());
                anchor = parent == null ? world : parent.mapping.mapPoint(world);
            }
            Node node = new Node(e.path(), e.portal(), parent,
                PortalPrism.of(e.portal(), anchor, diagonal + normalDistance));
            byPath.put(e.path(), node);
            if (parent == null) roots.add(node);
            else parent.children.add(node);
        }
        if (roots.isEmpty()) return EMPTY;
        // Siblings in priority order (see the class comment), identical on server and client.
        prioritize(roots);
        List<Node> all = new ArrayList<>(byPath.size());
        for (List<Node> level = roots; !level.isEmpty(); ) {
            all.addAll(level);
            List<Node> next = new ArrayList<>(level.size());
            for (Node n : level) next.addAll(n.children);
            level = next;
        }
        return new IplProjectionTree(sub, List.copyOf(roots), List.copyOf(all));
    }

    /** Sorts every sibling list (roots, children of each node) by sibling priority. */
    private static void prioritize(List<Node> siblings) {
        if (siblings.size() > 1) {
            Map<Node, Integer> rank = new IdentityHashMap<>();
            for (Node n : siblings) rank.put(n, upstreamRank(n, siblings));
            siblings.sort(Comparator.<Node>comparingInt(rank::get).thenComparing(n -> n.path));
        }
        for (Node n : siblings) prioritize(n.children);
    }

    /**
     * How many siblings {@code node} lies strictly downstream of (0 = nothing in front of it).
     * Same rule as {@code IplAtlasStraddleSession.upstreamRank}.
     */
    private static int upstreamRank(Node node, List<Node> siblings) {
        int rank = 0;
        for (Node other : siblings) {
            if (other != node && PortalOrder.strictlyDownstreamOf(node.portal, node.prism, other.portal, other.prism)) {
                rank++;
            }
        }
        return rank;
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

    /** Session sets changed (start/end/flip); every cached tree is stale. */
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
        if (client ? IplStraddleSessionStore.isIdle() : IplAtlasStraddleSession.isIdle()) {
            return EMPTY;
        }
        Cache cache = CACHE.get();
        // One clock per side (client levels keep independent game times).
        long stamp = client
            ? IplClientProjections.clientStamp()
            : level instanceof ServerLevel server ? server.getServer().getTickCount() : 0L;
        long gen = generation;
        if (cache.stamp != stamp || cache.generation != gen) {
            cache.trees.clear();
            cache.stamp = stamp;
            cache.generation = gen;
        }
        IplProjectionTree tree = cache.trees.get(sub);
        if (tree == null) {
            List<Entry> entries = client
                ? IplClientProjections.treeEntries(sub)
                : IplAtlasStraddleSession.treeEntries(sub);
            tree = build(sub, entries);
            cache.trees.put(sub, tree);
        }
        return tree;
    }

    // ------------------------------------------------------------------ frame queries

    /**
     * The pose a plot point of {@code sub} is physically drawn/collided at as seen from
     * {@code ctx}, given its REAL world position: the piece's composite mapping applied to
     * {@code realPose} when that piece's image lives in {@code ctx}; {@code realPose}
     * itself for the native piece in the native level; otherwise null (the point is not
     * physically present in {@code ctx}).
     */
    @Nullable
    public static Pose3dc piecePoseIn(SubLevel sub, Level ctx, Pose3dc realPose, Vec3 realWorld) {
        IplProjectionTree tree = of(sub);
        Node piece = tree.pieceAt(realWorld);
        if (piece == null) return sub.getLevel() == ctx ? realPose : null;
        return piece.destDim() == ctx.dimension() ? piece.mapping.mapPose(realPose) : null;
    }

    /**
     * Where a REAL-frame world point of {@code sub} physically is as seen from {@code ctx}:
     * its piece's mapped position when that piece lives in {@code ctx}, the point itself
     * for the native piece in the native level. When the piece is elsewhere, falls back to
     * the native position (native level) or the first image into {@code ctx}, so distances
     * stay finite and continuous.
     */
    @Nullable
    public static Vec3 worldPointIn(SubLevel sub, Level ctx, Vec3 realWorld) {
        IplProjectionTree tree = of(sub);
        if (tree.isEmpty()) return null;
        Node piece = tree.pieceAt(realWorld);
        if (piece != null && piece.destDim() == ctx.dimension()) return piece.mapping.mapPoint(realWorld);
        if (sub.getLevel() == ctx) return null; // native position (unchanged)
        for (Node n : tree.nodes()) {
            if (n.destDim() == ctx.dimension()) return n.mapping.mapPoint(realWorld);
        }
        return null;
    }

    /**
     * Every frame through which {@code sub} is present in {@code ctx}: the native frame
     * (node null) when native, plus each node whose image lives there.
     */
    public static void forEachFrameIn(SubLevel sub, Level ctx, Consumer<Node> visitor) {
        IplProjectionTree tree = of(sub);
        if (sub.getLevel() == ctx) visitor.accept(null);
        for (Node n : tree.nodes()) {
            if (n.destDim() == ctx.dimension()) visitor.accept(n);
        }
    }
}
