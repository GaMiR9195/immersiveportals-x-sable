package ipl.sable.transit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.mixin.ClipContextAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
 * straddling ship is, physically, several PIECES (see {@link IplProjectionTree}): the native
 * piece at the real pose, and one piece per session at that session's composite portal
 * mapping. A frame cast walks the ray through the plot at the frame's pose and stops only on
 * blocks of its own piece (labels; split blocks of an aperture zone by the side of the plane),
 * so material that went past a portal frame is hit where it really is, and a block straddling
 * the portal plane is hit on its visible part, never on a phantom twin behind the plane.
 *
 * <p>Один алгоритм для сервера и клиента, для прямых и цепочечных проекций: каждый кадр
 * (кусок) кастуется отдельно только по своим блокам, ближайшее попадание побеждает.
 * Так по кораблю можно кликнуть и после выхода из второго портала, а стрелы, линия
 * видимости мобов и проверки досягаемости на сервере видят проекции.
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

    /** Hits this close behind the aperture plane of a split block still count (rounding). */
    private static final double PLANE_EPS = 1.0e-4;

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
            ? IplStraddleSessionStore.isIdle()
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
     * Clip one frame: the ray cast through the plot at that frame's pose, hitting only the
     * blocks of that frame's piece ({@link IplStraddlePoseMap.PieceCut}; {@code node == null}
     * is the native piece). A block an aperture zone splits counts only when the hit lies on
     * the piece's side of the aperture plane, so a block straddling the portal is hit on its
     * visible part, never on a phantom twin. Blocks are read from the ship's OWN level (the plot
     * owner) block by block, without any overlay or cross-level bridging.
     */
    @Nullable
    private static FrameHit clipFrame(ClipContext template, Vec3 from, Vec3 dir, double length, SubLevel sub,
                                      IplProjectionTree tree, @Nullable IplProjectionTree.Node node,
                                      Pose3dc realPose) {
        Pose3dc pose = node == null ? realPose : node.mapping.mapPose(realPose);
        IplStraddlePoseMap.PieceCut cut = IplStraddlePoseMap.PieceCut.of(tree, node);
        Level blocks = sub.getLevel();
        ClipContextAccessor access = (ClipContextAccessor) template;
        ClipContext plot = new ClipContext(
            pose.transformPositionInverse(from), pose.transformPositionInverse(from.add(dir.scale(length))),
            access.ipl$getBlock(), access.ipl$getFluid(), access.ipl$getCollisionContext());
        BlockHitResult hit = BlockGetter.traverseBlocks(plot.getFrom(), plot.getTo(), plot, (c, pos) -> {
            if (!cut.keeps(pos)) return null;
            BlockHitResult h = clipBlock(blocks, c, pos);
            if (h == null) return null;
            IplStraddlePoseMap.LocalHalfSpace plane = cut.planeFor(pos.getX(), pos.getY(), pos.getZ());
            Vec3 at = h.getLocation();
            return plane == null || plane.signedDistance(at.x, at.y, at.z) >= -PLANE_EPS ? h : null;
        }, c -> null);
        if (hit == null) return null;
        return new FrameHit(hit, pose.transformPosition(hit.getLocation()).subtract(from).dot(dir), sub, node);
    }

    /** One block of vanilla's {@code BlockGetter.clip}: the nearer of its shape and fluid hits. */
    @Nullable
    private static BlockHitResult clipBlock(Level blocks, ClipContext c, BlockPos pos) {
        BlockState state = blocks.getBlockState(pos);
        FluidState fluid = blocks.getFluidState(pos);
        Vec3 a = c.getFrom();
        Vec3 b = c.getTo();
        BlockHitResult block = blocks.clipWithInteractionOverride(a, b, pos, c.getBlockShape(state, blocks, pos), state);
        BlockHitResult liquid = c.getFluidShape(fluid, blocks, pos).clip(a, b, pos);
        double db = block == null ? Double.MAX_VALUE : a.distanceToSqr(block.getLocation());
        double dl = liquid == null ? Double.MAX_VALUE : a.distanceToSqr(liquid.getLocation());
        return db <= dl ? block : liquid;
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
