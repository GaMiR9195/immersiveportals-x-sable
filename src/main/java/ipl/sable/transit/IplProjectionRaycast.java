package ipl.sable.transit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.mixin.IplClipContextAccessor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Portal-aware raycasting against straddling sub-levels, for BOTH logical sides.
 *
 * <p>Sable's clip overlay raycasts every intersecting sub-level with one pose. A
 * straddling ship is, physically, several disjoint PIECES (see {@link IplProjectionTree}):
 * the native piece at the real pose, and one piece per session at that session's
 * composite portal mapping. Each piece is a ray-convex region (native: outside every root
 * tube; node: inside its keep-tube intersection, outside its children's tubes), so a
 * frame cast walks the ray only through its own region and skips holes exactly at their
 * boundaries. A block straddling the portal plane is therefore hit on its visible cut
 * face, never on a phantom twin behind the plane.
 *
 * <p>Русский комментарий: раньше пик проекций был только на клиенте, только для прямых
 * (не цепочечных) проекций, а фильтр "через какую половину" был одной плоскостью
 * первого портала. Поэтому после выхода из второго портала по кораблю нельзя было
 * кликнуть вообще, а сервер (стрелы, линия видимости мобов, проверки досягаемости)
 * проекций не видел. Здесь один общий алгоритм для сервера и клиента: каждый кадр
 * (кусок) кастуется отдельно только внутри своей области, ближайшее попадание побеждает.
 *
 * <p>Hits stay in PLOT coordinates (Sable's sub-level hit convention); distances are
 * measured in the queried level's world units along the ray, so every frame and the
 * stock result compare directly.
 *
 * <p>Usage from a level's {@code clip} override (no lambdas, so the merged mixin method
 * can call {@code BlockGetter.super.clip} directly):
 * <pre>
 * Plan plan = IplProjectionRaycast.plan(level, ctx);
 * if (plan == null) return super.clip(ctx);
 * plan.mask(); try { base = super.clip(ctx); } finally { plan.unmask(); }
 * return plan.finish(base);
 * </pre>
 */
public final class IplProjectionRaycast {

    private IplProjectionRaycast() {}

    private static final int MAX_SKIPS = 8;
    private static final double SKIP_EPS = 1.0e-4;

    /** A frame hit: plot-coordinate result, world ray distance, owning ship and piece. */
    public record FrameHit(BlockHitResult hit, double distance, SubLevel sub,
                           @Nullable IplProjectionTree.Node node) {}

    /**
     * The frame hit that won the most recent full {@link Plan#finish} on this thread, or
     * null when the stock result won. Lets pick code learn WHICH representation was hit
     * right after calling {@code level.clip} (same thread, synchronous).
     */
    private static final ThreadLocal<FrameHit> LAST_WINNER = new ThreadLocal<>();

    @Nullable
    public static FrameHit lastWinner(@Nullable HitResult forHit) {
        FrameHit last = LAST_WINNER.get();
        return last != null && last.hit() == forHit ? last : null;
    }

    /** The pose Sable's own overlay uses for {@code sub} when queried from {@code level}. */
    public static Pose3dc rayPose(Level level, SubLevel sub) {
        if (level instanceof LevelPoseProviderExtension ext) return ext.sable$getPose(sub);
        return sub.logicalPose();
    }

    private static boolean idle(Level level) {
        return level.isClientSide()
            ? ipl.sable.client.IplStraddleSessionStore.isIdle()
            : IplAtlasStraddleSession.isIdle();
    }

    /**
     * Ships whose geometry in {@code level} is not what Sable's single-pose overlay sees:
     * foreign ships (present only as images) and native ships with live sessions.
     */
    public static boolean needsFrames(SubLevel s, Level level) {
        return s.getLevel() != level || !IplProjectionTree.of(s).isEmpty();
    }

    /** A prepared portal-aware clip; null from {@link #plan} means "stock is exact". */
    public static final class Plan {
        private final Level level;
        private final ClipContext ctx;
        private final ClipContextExtension ext;
        private final Vec3 from;
        private final Vec3 to;
        private final List<SubLevel> handled;
        @Nullable private final SubLevel ignored;
        @Nullable private final Predicate<SubLevel> previous;

        private Plan(Level level, ClipContext ctx, Vec3 from, Vec3 to, List<SubLevel> handled) {
            this.level = level;
            this.ctx = ctx;
            this.ext = (ClipContextExtension) ctx;
            this.from = from;
            this.to = to;
            this.handled = handled;
            this.ignored = ext.sable$getIgnoredSubLevel();
            this.previous = ext.sable$getSubLevelIgnoring();
        }

        /** Hide the handled ships from the stock overlay (their frames are cast here). */
        public void mask() {
            Set<SubLevel> mask = Collections.newSetFromMap(new IdentityHashMap<>());
            mask.addAll(handled);
            Predicate<SubLevel> prev = previous;
            ext.sable$setSubLevelIgnoring(prev == null ? mask::contains : s -> mask.contains(s) || prev.test(s));
        }

        public void unmask() {
            ext.sable$setSubLevelIgnoring(previous);
        }

        /** Nearest of the stock result and every frame of every handled ship. */
        public BlockHitResult finish(BlockHitResult base) {
            BlockHitResult best = base;
            FrameHit winner = null;
            double bestDistance = base == null || base.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE : stockDistance(level, from, base);
            for (SubLevel s : handled) {
                if (s == ignored || (previous != null && previous.test(s))) continue;
                FrameHit hit = clipSubLevel(level, ctx, from, to, s, true, null);
                if (hit != null && hit.distance() < bestDistance) {
                    best = hit.hit();
                    bestDistance = hit.distance();
                    winner = hit;
                }
            }
            LAST_WINNER.set(winner);
            return best;
        }
    }

    /** Prepare a portal-aware clip, or null when the stock overlay is already exact. */
    @Nullable
    public static Plan plan(Level level, ClipContext ctx) {
        ClipContextExtension ext = (ClipContextExtension) ctx;
        if (ext.sable$doNotProject() || idle(level)) {
            return null;
        }
        Vec3 from = worldEndpoint(level, ctx.getFrom());
        Vec3 to = worldEndpoint(level, ctx.getTo());
        List<SubLevel> handled = null;
        for (SubLevel s : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(from, to))) {
            if (s.isRemoved() || !needsFrames(s, level)) continue;
            if (handled == null) handled = new ArrayList<>(2);
            if (!handled.contains(s)) handled.add(s);
        }
        if (handled == null) {
            LAST_WINNER.remove();
            return null;
        }
        return new Plan(level, ctx, from, to, handled);
    }

    /**
     * Nearest hit of one ship over its frames present in {@code level}: the native frame
     * (when {@code includeNative} and the ship is native here) and every node whose image
     * lives in {@code level} and passes {@code nodeFilter} (nullable = all).
     */
    @Nullable
    public static FrameHit clipSubLevel(Level level, ClipContext template, Vec3 from, Vec3 to, SubLevel sub,
                                        boolean includeNative,
                                        @Nullable Predicate<IplProjectionTree.Node> nodeFilter) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0e-9) return null;
        Vec3 dir = delta.scale(1.0 / length);
        IplProjectionTree tree = IplProjectionTree.of(sub);
        Pose3dc pose = rayPose(level, sub);
        FrameHit best = null;
        if (includeNative && sub.getLevel() == level) {
            best = clipFrame(template, from, dir, length, sub, tree, null, pose);
        }
        for (IplProjectionTree.Node node : tree.nodes()) {
            if (node.destDim() != level.dimension()) continue;
            if (nodeFilter != null && !nodeFilter.test(node)) continue;
            FrameHit hit = clipFrame(template, from, dir, length, sub, tree, node, pose);
            if (hit != null && (best == null || hit.distance() < best.distance())) best = hit;
        }
        return best;
    }

    /**
     * Clip one frame: the ray restricted to that piece's region. {@code node == null} is
     * the native piece (holes = root tubes); otherwise the keep interval of the node minus
     * its children's tubes. Blocks are read from the ship's OWN level (the plot owner), so
     * no cross-level bridging is involved.
     */
    @Nullable
    private static FrameHit clipFrame(ClipContext template, Vec3 from, Vec3 dir, double length, SubLevel sub,
                                      IplProjectionTree tree, @Nullable IplProjectionTree.Node node,
                                      Pose3dc realPose) {
        double keepFrom = 0.0;
        double keepTo = length;
        List<double[]> holes = new ArrayList<>(2);
        if (node == null) {
            for (IplProjectionTree.Node root : tree.roots()) {
                double[] iv = root.beyondInterval(from, dir);
                if (iv != null && iv[1] >= 0.0 && iv[0] <= length) holes.add(iv);
            }
        } else {
            double[] keep = node.keepInterval(from, dir);
            if (keep == null) return null;
            keepFrom = Math.max(keepFrom, keep[0]);
            keepTo = Math.min(keepTo, keep[1]);
            if (keepFrom > keepTo) return null;
            for (IplProjectionTree.Node child : node.children) {
                double[] iv = child.beyondInterval(from, dir);
                if (iv != null && iv[1] >= keepFrom && iv[0] <= keepTo) holes.add(iv);
            }
        }
        Pose3dc pose = node == null ? realPose : node.mapping.mapPose(realPose);
        Level blocks = sub.getLevel();
        IplClipContextAccessor access = (IplClipContextAccessor) template;
        double t = keepFrom;
        for (int i = 0; i < MAX_SKIPS; i++) {
            t = skipHoles(holes, t);
            if (t >= keepTo) return null;
            Vec3 a = from.add(dir.scale(t));
            Vec3 b = from.add(dir.scale(keepTo));
            ClipContext step = new ClipContext(
                pose.transformPositionInverse(a), pose.transformPositionInverse(b),
                access.ipl$getBlock(), access.ipl$getFluid(), access.ipl$getCollisionContext());
            ((ClipContextExtension) step).sable$setDoNotProject(true);
            BlockHitResult hit = blocks.clip(step);
            if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
            Vec3 world = pose.transformPosition(hit.getLocation());
            double th = world.subtract(from).dot(dir);
            double[] hole = holeAt(holes, th);
            if (hole == null) return new FrameHit(hit, th, sub, node);
            t = Math.max(hole[1], th) + SKIP_EPS;
        }
        return null;
    }

    private static double skipHoles(List<double[]> holes, double t) {
        boolean moved = true;
        for (int guard = 0; moved && guard < 8; guard++) {
            moved = false;
            for (double[] h : holes) {
                if (t >= h[0] && t <= h[1]) {
                    t = h[1] + SKIP_EPS;
                    moved = true;
                }
            }
        }
        return t;
    }

    @Nullable
    private static double[] holeAt(List<double[]> holes, double t) {
        for (double[] h : holes) if (t >= h[0] && t <= h[1]) return h;
        return null;
    }

    /**
     * Ray endpoints given in plot space are projected out with the same pose Sable's
     * overlay uses, so the stock pass and the frame passes trace one identical ray.
     */
    private static Vec3 worldEndpoint(Level level, Vec3 p) {
        SubLevel owner = Sable.HELPER.getContaining(level, p);
        if (owner == null) return p;
        return rayPose(level, owner).transformPosition(p);
    }

    /** World ray distance of a stock hit (plot hits measured in their owner's frame). */
    private static double stockDistance(Level level, Vec3 from, BlockHitResult hit) {
        SubLevel owner = Sable.HELPER.getContaining(level, hit.getBlockPos());
        if (owner == null) return hit.getLocation().distanceTo(from);
        Vec3 localFrom = rayPose(level, owner).transformPositionInverse(from);
        return hit.getLocation().distanceTo(localFrom);
    }
}
