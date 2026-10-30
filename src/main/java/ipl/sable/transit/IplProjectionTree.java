package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * PROJECTION TREE of one sub-level: every live straddle session (root = the real body
 * crossing a portal of its own level, child = an image crossing a portal of its
 * destination level) as one node, with the exact region geometry the native contact clip
 * uses.
 *
 * <p>Русский комментарий (почему так): раньше каждое "взаимодействие" (пик, дистанция
 * досягаемости, контур, нажимные плиты, сущности) само решало, в каком кадре находится
 * точка корабля — по одной плоскости первого портала. Для нескольких порталов и цепочек
 * (выход из 2-го портала и дальше) это давало неверный кадр, отсюда "не могу
 * взаимодействовать вообще". Теперь есть одна истина — дерево сессий и та же
 * геометрия трубок, что и в физике (natives: KEEP = пересечение трубок пути,
 * EXCLUDE = трубки дочерних порталов). Точка плота принадлежит ровно одному куску:
 * родному (не прошла ни один корневой портал) или узлу пути, и все потребители
 * спрашивают именно это.
 *
 * <p>A plot point belongs to exactly ONE piece: the native piece (inside no root tube) or
 * the deepest node whose tube chain it went through. Region tests reproduce
 * {@code IplAtlasStraddleSession}'s regions (same skin, lateral margin and depth bound),
 * so interaction, collision images and physics always agree on where a block physically
 * is. Built from server sessions or from the client session mirror; cached per thread and
 * tick, invalidated whenever a session set changes.
 */
public final class IplProjectionTree {

    /** Same constants as the physics regions ({@code IplAtlasStraddleSession}). */
    public static final double SKIN = 1.0e-3;
    public static final double LATERAL_MARGIN = 0.25;
    /** Lateral half-extent of a "wide" (half-space) node, same as the physics payload. */
    public static final double WIDE_EXTENT = 1.0e6;

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
        final double halfW;
        final double halfH;
        final double maxDepth;
        final int depth;
        /** Half-space (depth-bounded only) instead of the aperture tube; see IplAtlasStraddleSession. */
        public final boolean wide;

        Node(String path, Portal portal, @Nullable Node parent, double maxDepth, boolean wide) {
            this.path = path;
            this.portal = portal;
            this.parent = parent;
            this.local = IplStraddlePoseMap.StraddleMapping.of(portal);
            this.mapping = parent == null ? local : parent.mapping.then(local);
            this.wide = wide;
            this.halfW = wide ? WIDE_EXTENT : portal.getWidth() * 0.5 + LATERAL_MARGIN;
            this.halfH = wide ? WIDE_EXTENT : portal.getHeight() * 0.5 + LATERAL_MARGIN;
            this.maxDepth = maxDepth;
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

        /** Point in this node's SOURCE frame lies in the tube beyond its portal plane. */
        public boolean beyondContains(Vec3 w) {
            Vec3 o = portal.getOriginPos();
            double rx = w.x - o.x, ry = w.y - o.y, rz = w.z - o.z;
            Vec3 n = portal.getNormal();
            // Crossing direction is -normal (the transit controller's sourceToDest).
            double depthBeyond = -(rx * n.x + ry * n.y + rz * n.z);
            if (depthBeyond < -SKIN || depthBeyond > maxDepth) return false;
            Vec3 aw = portal.getAxisW();
            Vec3 ah = portal.getAxisH();
            return Math.abs(rx * aw.x + ry * aw.y + rz * aw.z) <= halfW
                && Math.abs(rx * ah.x + ry * ah.y + rz * ah.z) <= halfH;
        }

        /**
         * Ray-parameter interval [t0, t1] where {@code from + t*dir} (SOURCE frame of this
         * node, {@code dir} unit) is inside the beyond tube; null when never.
         */
        @Nullable
        public double[] beyondInterval(Vec3 from, Vec3 dir) {
            Vec3 o = portal.getOriginPos();
            Vec3 rel = from.subtract(o);
            double[] t = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
            Vec3 inward = portal.getNormal().scale(-1.0);
            if (!slab(t, rel.dot(inward), dir.dot(inward), -SKIN, maxDepth)) return null;
            Vec3 aw = portal.getAxisW();
            if (!slab(t, rel.dot(aw), dir.dot(aw), -halfW, halfW)) return null;
            Vec3 ah = portal.getAxisH();
            if (!slab(t, rel.dot(ah), dir.dot(ah), -halfH, halfH)) return null;
            return t[0] <= t[1] ? t : null;
        }

        private static boolean slab(double[] t, double s0, double sd, double lo, double hi) {
            if (Math.abs(sd) < 1.0e-12) return s0 >= lo && s0 <= hi;
            double ta = (lo - s0) / sd;
            double tb = (hi - s0) / sd;
            if (ta > tb) {
                double swap = ta;
                ta = tb;
                tb = swap;
            }
            if (ta > t[0]) t[0] = ta;
            if (tb < t[1]) t[1] = tb;
            return t[0] <= t[1];
        }

        /**
         * Membership of a point given in this node's DESTINATION frame: it went through
         * every portal of the path (all ancestor tubes) and through none of the children.
         */
        public boolean regionContains(Vec3 wDest) {
            for (Node child : children) {
                if (child.beyondContains(wDest)) return false;
            }
            Vec3 w = wDest;
            for (Node n = this; n != null; n = n.parent) {
                w = n.local.unmapPoint(w);
                if (!n.beyondContains(w)) return false;
            }
            return true;
        }

        /**
         * Ray interval (DESTINATION frame) inside the KEEP set of this node: the
         * intersection of every ancestor tube mapped forward. Convex, so one interval.
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

    /** Nodes whose image lives in {@code dim}. */
    public List<Node> nodesInto(ResourceKey<Level> dim) {
        List<Node> out = null;
        for (Node n : all) {
            if (n.destDim() == dim) {
                if (out == null) out = new ArrayList<>(2);
                out.add(n);
            }
        }
        return out == null ? List.of() : out;
    }

    /**
     * The piece a REAL-frame world point (body's own level, real pose) belongs to: null for
     * the native piece, otherwise the deepest node it went through. Overlapping root tubes
     * (two adjacent portals) resolve to the first session, like the physics images
     * (each of which keeps its own tube) would both claim it.
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

    /** Native piece membership of a real-frame world point (no root tube contains it). */
    public boolean nativeContains(Vec3 realWorld) {
        for (Node root : roots) if (root.beyondContains(realWorld)) return false;
        return true;
    }

    // ------------------------------------------------------------------ building

    /** One session description, parents before children; {@code wide} = half-space regions. */
    public record Entry(String path, String via, Portal portal, boolean wide) {
        public Entry(String path, String via, Portal portal) {
            this(path, via, portal, false);
        }
    }

    public static IplProjectionTree build(SubLevel sub, List<Entry> entries) {
        if (entries.isEmpty()) return EMPTY;
        Map<String, Node> byPath = new HashMap<>();
        List<Node> roots = new ArrayList<>(2);
        List<Node> all = new ArrayList<>(entries.size());
        var box = sub.getPlot().getBoundingBox();
        double dx = box.maxX() - box.minX() + 1.0;
        double dy = box.maxY() - box.minY() + 1.0;
        double dz = box.maxZ() - box.minZ() + 1.0;
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vec3 center = sub.logicalPose().transformPosition(new Vec3(
            (box.minX() + box.maxX() + 1.0) * .5,
            (box.minY() + box.maxY() + 1.0) * .5,
            (box.minZ() + box.maxZ() + 1.0) * .5));
        // Parents strictly before children, whatever order the session map iterated in
        // (a re-opened parent must not orphan its still-live chain for a tick).
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(java.util.Comparator.comparingInt(e -> pathDepth(e.path())));
        for (Entry e : ordered) {
            if (byPath.containsKey(e.path()) || e.portal() == null) continue;
            if (Math.abs(e.portal().getScaling() - 1.0) > 1e-9) continue;
            Node parent = e.via().isEmpty() ? null : byPath.get(e.via());
            if (!e.via().isEmpty() && parent == null) continue; // orphan chain this tick
            Vec3 c = parent == null ? center : parent.mapping.mapPoint(center);
            double normalDistance = Math.abs(c.subtract(e.portal().getOriginPos()).dot(e.portal().getNormal()));
            Node node = new Node(e.path(), e.portal(), parent, diagonal + normalDistance, e.wide());
            byPath.put(e.path(), node);
            all.add(node);
            if (parent == null) roots.add(node);
            else parent.children.add(node);
        }
        return all.isEmpty() ? EMPTY : new IplProjectionTree(sub, List.copyOf(roots), List.copyOf(all));
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
        if (client ? ipl.sable.client.IplStraddleSessionStore.isIdle() : IplAtlasStraddleSession.isIdle()) {
            return EMPTY;
        }
        Cache cache = CACHE.get();
        // One clock per side (client levels keep independent game times).
        long stamp = client
            ? ipl.sable.client.IplClientProjections.clientStamp()
            : level instanceof net.minecraft.server.level.ServerLevel server ? server.getServer().getTickCount() : 0L;
        long gen = generation;
        if (cache.stamp != stamp || cache.generation != gen) {
            cache.trees.clear();
            cache.stamp = stamp;
            cache.generation = gen;
        }
        IplProjectionTree tree = cache.trees.get(sub);
        if (tree == null) {
            List<Entry> entries = client
                ? ipl.sable.client.IplClientProjections.treeEntries(sub)
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
     * the native position (native level) or the first image into {@code ctx} -- the
     * historical behaviour, so distances stay finite and continuous.
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
    public static void forEachFrameIn(SubLevel sub, Level ctx, java.util.function.Consumer<Node> visitor) {
        IplProjectionTree tree = of(sub);
        if (sub.getLevel() == ctx) visitor.accept(null);
        for (Node n : tree.nodes()) {
            if (n.destDim() == ctx.dimension()) visitor.accept(n);
        }
    }
}
