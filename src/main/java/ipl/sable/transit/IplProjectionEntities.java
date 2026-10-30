package ipl.sable.transit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Entity-vs-block mechanics for ships that physically exist as several PIECES (see
 * {@link IplProjectionTree}): which level a plot block's game logic runs in, which entities
 * physically touch a plot-space box, and whether an entity's in-block scan really touches
 * a block of the piece it scanned.
 *
 * <p>Русский комментарий (почему так): нажимные плиты, растяжки, кнопки под стрелами,
 * кактусы, паутина и т.п. на проекции корабля не работали по двум причинам.
 * 1) Логика блока ({@code entityInside}, {@code stepOn}, {@code fallOn},
 * {@code onProjectileHit}) получала уровень СУЩНОСТИ, а блок живёт в уровне-владельце
 * корабля: запись в чужой плот отклонялась, тики планировались в уровень, где этого
 * чанка нет. Теперь логика всегда выполняется в уровне-владельце плота.
 * 2) Плита ищет сущности в своём плот-боксе, а Sable переводит бокс в мир родной позой
 * и ищет только в своём уровне — игрок, стоящий на образе (за порталом, возможно в
 * другом измерении), не находился. Теперь бокс переводится в КАЖДЫЙ кусок, которого он
 * касается, и поиск идёт в уровне этого куска; сущность засчитывается только если точка
 * бокса, ближайшая к ней, принадлежит именно этому куску (иначе за порталом "сзади"
 * нажимались бы срезанные блоки).
 */
public final class IplProjectionEntities {

    private IplProjectionEntities() {}

    /** Boxes up to this size use corner sampling to skip pieces they cannot touch. */
    private static final double SAMPLED_BOX_LIMIT = 4.0;

    static boolean idle(Level level) {
        return level.isClientSide()
            ? ipl.sable.client.IplStraddleSessionStore.isIdle()
            : IplAtlasStraddleSession.isIdle();
    }

    // ------------------------------------------------------------------ owner routing

    /**
     * The live ship whose plot {@code ctx} sees at {@code pos}. The container lookup is
     * bridged ({@code IplPlotBridgeMixin}), so this is also how a projected ship owned by
     * another level is found -- exactly the ship whose blocks {@code ctx} reads there.
     */
    @Nullable
    public static SubLevel plotOwner(Level ctx, BlockPos pos) {
        SubLevelContainer container = SubLevelContainer.getContainer(ctx);
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (container == null || !container.inBounds(chunkX, chunkZ)) return null;
        LevelPlot plot = container.getPlot(chunkX, chunkZ);
        SubLevel owner = plot == null ? null : plot.getSubLevel();
        return owner != null && !owner.isRemoved() ? owner : null;
    }

    /**
     * The level that authoritatively stores the block {@code ctx} sees at {@code pos}: the
     * owning ship's level for a projected (foreign) plot, {@code ctx} otherwise. Block
     * logic must run there -- its writes, scheduled ticks and block entities live there.
     */
    public static Level owningLevel(Level ctx, BlockPos pos) {
        if (ctx == null || pos == null || idle(ctx)) return ctx;
        SubLevel owner = plotOwner(ctx, pos);
        Level own = owner == null ? null : owner.getLevel();
        return own != null ? own : ctx;
    }

    // ------------------------------------------------------------------ frames

    /** Pose of one frame of {@code sub}: the real pose (native) or the node's image pose. */
    public static Pose3dc framePose(SubLevel sub, @Nullable IplProjectionTree.Node node) {
        Pose3dc real = sub.logicalPose();
        return node == null ? real : node.mapping.mapPose(real);
    }

    /** The level a frame of {@code sub} lives in (null when that level is not loaded here). */
    @Nullable
    public static Level frameLevel(SubLevel sub, @Nullable IplProjectionTree.Node node) {
        Level own = sub.getLevel();
        if (node == null || own == null) return own;
        ResourceKey<Level> dim = node.destDim();
        if (own.dimension() == dim) return own;
        if (own.isClientSide()) return ipl.sable.client.IplClientProjections.clientLevel(dim);
        return own instanceof ServerLevel server ? server.getServer().getLevel(dim) : null;
    }

    /**
     * The piece owning the point of plot box {@code plotBox} nearest to the plot-space
     * probe: where an entity at that probe actually touches the box.
     */
    @Nullable
    static IplProjectionTree.Node pieceNear(SubLevel sub, IplProjectionTree tree, AABB plotBox, Vec3 plotProbe) {
        Vec3 nearest = new Vec3(
            Mth.clamp(plotProbe.x, plotBox.minX, plotBox.maxX),
            Mth.clamp(plotProbe.y, plotBox.minY, plotBox.maxY),
            Mth.clamp(plotProbe.z, plotBox.minZ, plotBox.maxZ));
        return tree.pieceAt(sub.logicalPose().transformPosition(nearest));
    }

    static AABB toWorld(AABB plotBox, Pose3dc pose) {
        return new BoundingBox3d(plotBox).transform(pose).toMojang();
    }

    static AABB toPlot(AABB worldBox, Pose3dc pose) {
        return new BoundingBox3d(worldBox).transformInverse(pose).toMojang();
    }

    /**
     * Frames of {@code sub} a plot box can physically touch: every frame for big boxes,
     * otherwise the pieces of its corners and center (a piece is a convex tube or its
     * complement, so a small box crossing a boundary has a sample on each side).
     */
    static List<IplProjectionTree.Node> touchedPieces(SubLevel sub, IplProjectionTree tree, AABB plotBox) {
        List<IplProjectionTree.Node> out = new ArrayList<>(3);
        if (plotBox.getXsize() > SAMPLED_BOX_LIMIT || plotBox.getYsize() > SAMPLED_BOX_LIMIT
            || plotBox.getZsize() > SAMPLED_BOX_LIMIT) {
            out.add(null);
            out.addAll(tree.nodes());
            return out;
        }
        Pose3dc real = sub.logicalPose();
        for (int i = 0; i < 9; i++) {
            Vec3 p = i == 8 ? plotBox.getCenter() : new Vec3(
                (i & 1) == 0 ? plotBox.minX : plotBox.maxX,
                (i & 2) == 0 ? plotBox.minY : plotBox.maxY,
                (i & 4) == 0 ? plotBox.minZ : plotBox.maxZ);
            IplProjectionTree.Node piece = tree.pieceAt(real.transformPosition(p));
            if (!out.contains(piece)) out.add(piece);
        }
        return out;
    }

    // ------------------------------------------------------------------ raw entity access

    @SuppressWarnings("unchecked")
    @Nullable
    private static LevelEntityGetter<Entity> getterOf(@Nullable Level level) {
        if (!(level instanceof qouteall.imm_ptl.core.ducks.IEWorld world)) return null;
        return world.portal_getEntityLookup();
    }

    /** Entities stored at {@code box} in {@code level}, ignoring every sub-level remap. */
    @SuppressWarnings("unchecked")
    static void rawGet(@Nullable Level level, AABB box, Consumer<Entity> sink) {
        LevelEntityGetter<Entity> getter = getterOf(level);
        if (getter == null) return;
        if (getter instanceof SubLevelInclusiveLevelEntityGetter<?> inclusive) {
            ((SubLevelInclusiveLevelEntityGetter<Entity>) inclusive).getIgnoringSubLevels(box, sink);
        } else {
            getter.get(box, sink);
        }
    }

    @SuppressWarnings("unchecked")
    static <U extends Entity> void rawGet(@Nullable Level level, EntityTypeTest<Entity, U> test, AABB box,
                                          AbortableIterationConsumer<U> sink) {
        LevelEntityGetter<Entity> getter = getterOf(level);
        if (getter == null) return;
        if (getter instanceof SubLevelInclusiveLevelEntityGetter<?> inclusive) {
            ((SubLevelInclusiveLevelEntityGetter<Entity>) inclusive).getIgnoringSubLevels(test, box, sink);
        } else {
            getter.get(test, box, sink);
        }
    }

    // ------------------------------------------------------------------ entity getter

    /** Does a plot-space query box of {@code level} need the piece-aware path? */
    @Nullable
    private static SubLevel framedPlotOwner(Level level, AABB box) {
        if (idle(level) || box.getSize() > SubLevelInclusiveLevelEntityGetter.MAX_GET_SIDE_LENGTH) return null;
        SubLevel sub = Sable.HELPER.getContaining(level, box.getCenter());
        if (sub == null || sub.isRemoved()) return null;
        if (sub.getLevel() == level && IplProjectionTree.of(sub).isEmpty()) return null; // plain ship: stock
        return sub;
    }

    /**
     * Piece-aware replacement of Sable's plot-box entity query (center of {@code box} in a
     * plot of {@code level}). Returns false when the stock code is exact.
     *
     * <p>Finds: plot-space entities of the ship (they live in its own level), and for every
     * piece the box touches, the entities in that piece's level at the box's image there,
     * counted only when the point of the box nearest to them belongs to that piece. Other
     * ships' plot-space entities are still found around the native piece (stock behaviour).
     */
    public static boolean plotQuery(Level level, LevelEntityGetter<Entity> delegate, AABB box, Consumer<Entity> consumer) {
        SubLevel sub = framedPlotOwner(level, box);
        if (sub == null) return false;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Consumer<Entity> once = e -> {
            if (seen.add(e)) consumer.accept(e);
        };
        Level own = sub.getLevel();
        if (own == level) delegate.get(box, once);
        else rawGet(own, box, once);

        for (IplProjectionTree.Node node : touchedPieces(sub, tree, box)) {
            Level frameLevel = frameLevel(sub, node);
            if (frameLevel == null) continue;
            Pose3dc pose = framePose(sub, node);
            AABB world = toWorld(box, pose);
            rawGet(frameLevel, world, e -> {
                if (pieceNear(sub, tree, box, pose.transformPositionInverse(e.getBoundingBox().getCenter())) == node) {
                    once.accept(e);
                }
            });
            if (node == null) {
                for (SubLevel other : Sable.HELPER.getAllIntersecting(frameLevel, new BoundingBox3d(world))) {
                    if (other == sub || other.isRemoved()) continue;
                    IplProjectionTree.forEachFrameIn(other, frameLevel, on ->
                        rawGet(other.getLevel(), toPlot(world, framePose(other, on)), once));
                }
            }
        }
        return true;
    }

    /** Typed/abortable twin of {@link #plotQuery}. */
    @SuppressWarnings("unchecked")
    public static <U extends Entity> boolean typedPlotQuery(Level level, LevelEntityGetter<Entity> delegate,
                                                            EntityTypeTest<Entity, U> test, AABB box,
                                                            AbortableIterationConsumer<U> consumer) {
        SubLevel sub = framedPlotOwner(level, box);
        if (sub == null) return false;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean[] aborted = {false};
        AbortableIterationConsumer<U> once = u -> {
            if (aborted[0]) return AbortableIterationConsumer.Continuation.ABORT;
            if (!seen.add(u)) return AbortableIterationConsumer.Continuation.CONTINUE;
            AbortableIterationConsumer.Continuation c = consumer.accept(u);
            if (c.shouldAbort()) aborted[0] = true;
            return c;
        };
        Level own = sub.getLevel();
        if (own == level) delegate.get(test, box, once);
        else rawGet(own, test, box, once);

        for (IplProjectionTree.Node node : touchedPieces(sub, tree, box)) {
            if (aborted[0]) return true;
            Level frameLevel = frameLevel(sub, node);
            if (frameLevel == null) continue;
            Pose3dc pose = framePose(sub, node);
            AABB world = toWorld(box, pose);
            rawGet(frameLevel, test, world, u -> {
                if (pieceNear(sub, tree, box, pose.transformPositionInverse(u.getBoundingBox().getCenter())) != node) {
                    return AbortableIterationConsumer.Continuation.CONTINUE;
                }
                return once.accept(u);
            });
            if (node == null && !aborted[0]) {
                for (SubLevel other : Sable.HELPER.getAllIntersecting(frameLevel, new BoundingBox3d(world))) {
                    if (aborted[0]) break;
                    if (other == sub || other.isRemoved()) continue;
                    IplProjectionTree.forEachFrameIn(other, frameLevel, on -> {
                        if (!aborted[0]) rawGet(other.getLevel(), test, toPlot(world, framePose(other, on)), once);
                    });
                }
            }
        }
        return true;
    }

    /**
     * Piece-aware replacement of one iteration of Sable's "other intersecting ships" loop
     * (plot-space entities of {@code other} physically inside world box {@code worldBox} of
     * {@code level}). Sable inverse-transforms with the native pose and queries the wrong
     * level for projected ships; here every frame of {@code other} present in
     * {@code level} is queried in the ship's own level. Returns false when stock is exact.
     */
    public static boolean otherShipQuery(Level level, @Nullable SubLevel other, AABB worldBox, Consumer<Entity> consumer) {
        if (other == null || other.isRemoved() || idle(level)) return false;
        IplProjectionTree tree = IplProjectionTree.of(other);
        if (other.getLevel() == level && tree.isEmpty()) return false;
        Pose3dc real = other.logicalPose();
        IplProjectionTree.forEachFrameIn(other, level, node ->
            rawGet(other.getLevel(), toPlot(worldBox, framePose(other, node)), e -> {
                // A plot-space entity is physically wherever its own piece is.
                if (tree.pieceAt(real.transformPosition(e.position())) == node) consumer.accept(e);
            }));
        return true;
    }

    /** Typed/abortable twin of {@link #otherShipQuery}. */
    public static <U extends Entity> boolean typedOtherShipQuery(Level level, @Nullable SubLevel other, AABB worldBox,
                                                                 EntityTypeTest<Entity, U> test,
                                                                 AbortableIterationConsumer<U> consumer) {
        if (other == null || other.isRemoved() || idle(level)) return false;
        IplProjectionTree tree = IplProjectionTree.of(other);
        if (other.getLevel() == level && tree.isEmpty()) return false;
        Pose3dc real = other.logicalPose();
        IplProjectionTree.forEachFrameIn(other, level, node ->
            rawGet(other.getLevel(), test, toPlot(worldBox, framePose(other, node)), u ->
                tree.pieceAt(real.transformPosition(u.position())) == node
                    ? consumer.accept(u) : AbortableIterationConsumer.Continuation.CONTINUE));
        return true;
    }

    // ------------------------------------------------------------------ in-block scans

    /** One-entry cache: the in-block scan asks for every block of one ship in a row. */
    private static final class FrameMemo {
        SubLevel sub;
        AABB bounds;
        IplProjectionTree tree;
        IplProjectionTree.Node node;
        boolean present;
    }

    private static final ThreadLocal<FrameMemo> FRAME_MEMO = ThreadLocal.withInitial(FrameMemo::new);

    /**
     * The level to run {@code entityInside} of plot block {@code pos} with, as found by an
     * in-block scan of {@code entity} (Sable's merged scan computes plot positions with the
     * collision frame chosen for the entity), or null to SKIP the call: the part of the
     * block the entity touches belongs to another piece (cut away in the scanned frame).
     * World blocks and plain ships return {@code ctx} unchanged.
     */
    @Nullable
    public static Level entityInsideLevel(Level ctx, BlockPos pos, Entity entity) {
        if (ctx == null || idle(ctx)) return ctx;
        SubLevel owner = plotOwner(ctx, pos);
        if (owner == null) return ctx;
        Level own = owner.getLevel() != null ? owner.getLevel() : ctx;
        IplProjectionTree tree = IplProjectionTree.of(owner);
        if (tree.isEmpty()) return own;

        AABB bounds = entity.getBoundingBox();
        FrameMemo memo = FRAME_MEMO.get();
        // Trees are rebuilt per tick and on every session change, so tree identity is an
        // exact staleness key; AABB identity changes whenever the entity moves.
        if (memo.sub != owner || memo.bounds != bounds || memo.tree != tree) {
            IplStraddlePoseMap.StraddleFrame frame =
                IplStraddlePoseMap.chooseCollisionFrame(owner, entity.level(), bounds.getCenter());
            memo.sub = owner;
            memo.bounds = bounds;
            memo.tree = tree;
            memo.node = frame == null ? null : frame.node();
            memo.present = memo.node != null || owner.getLevel() == entity.level();
        }
        if (!memo.present) return null;
        Vec3 probe = framePose(owner, memo.node).transformPositionInverse(bounds.getCenter());
        return pieceNear(owner, tree, new AABB(pos), probe) == memo.node ? own : null;
    }
}
