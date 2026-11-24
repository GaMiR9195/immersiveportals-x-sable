package ipl.sable.transit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entities against contraptions on straddling bodies. Create collides the entities around a
 * contraption once per tick in its own level, and Sable places that collision at the body's
 * logical pose. Here it runs once per piece the contraption is in: at the piece's pose, against
 * the entities of the piece's level and with that piece's blocks only. What went through a
 * portal pushes entities beyond it, and nothing pushes them at the entrance. A block lying across
 * an aperture counts for both pieces, each keeping only its side of the plane ({@link #cut}), like
 * the body's own blocks ({@link IplStraddleCollisionClip}).
 *
 * <p>A client collides only its own player, so it runs the pieces in the player's level
 * (contraptions of other levels too, see {@code ContraptionHandlerRemoteMixin}).
 */
public final class IplContraptionCollision {

    /**
     * One piece being collided: the pose override, the level searched, the blocks kept and, per
     * edge of the piece, the aperture plane keeping its side in the contraption's local frame.
     */
    private static final class Frame {
        final SubLevel sub;
        @Nullable final IplStraddlePoseMap.StraddleMapping mapping;
        final Level level;
        final Object2IntOpenHashMap<BlockPos> index;
        final IplContraptionShape.Split split;
        final int node;
        final IplStraddlePoseMap.LocalHalfSpace[] planes;
        /** Plane of the block whose shapes are read next; null keeps them whole. */
        @Nullable IplStraddlePoseMap.LocalHalfSpace current;

        Frame(SubLevel sub, @Nullable IplStraddlePoseMap.StraddleMapping mapping, Level level,
              Object2IntOpenHashMap<BlockPos> index, IplContraptionShape.Split split, int node,
              IplStraddlePoseMap.LocalHalfSpace[] planes) {
            this.sub = sub;
            this.mapping = mapping;
            this.level = level;
            this.index = index;
            this.split = split;
            this.node = node;
            this.planes = planes;
        }
    }

    private static final ThreadLocal<Frame> ACTIVE = new ThreadLocal<>();
    /** Frames active on any thread: the pose hooks read the thread-local only while one is. */
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger();

    private IplContraptionCollision() {}

    /** Wraps Create's {@code ContraptionCollider.collideEntities} for {@code contraption}. */
    public static void collide(Entity contraption, Runnable original) {
        Level own = contraption.level();
        Level only = own.isClientSide() ? IplClientProjections.playerLevel() : null;
        SubLevel sub = ACTIVE.get() == null ? Sable.HELPER.getContaining(contraption) : null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        IplContraptionShape shape = tree.isEmpty() ? null : IplContraptions.shape(contraption, 1.0f);
        if (shape == null) {
            if (only == null || only == own) original.run();
            return;
        }
        IplPieceMap.Edge[] edges = tree.edges();
        IplContraptionShape.Split split = shape.split(edges, tree.pieces().contraptionLabels(contraption.getUUID()));
        Object2IntOpenHashMap<BlockPos> index = new Object2IntOpenHashMap<>(shape.blocks.length);
        index.defaultReturnValue(-1);
        for (int i = 0; i < shape.blocks.length; i++) index.put(shape.blocks[i], i);
        BitSet nodes = split.nodes();
        for (int node = nodes.nextSetBit(0); node >= 0; node = nodes.nextSetBit(node + 1)) {
            IplProjectionTree.Node piece = tree.node(node);
            Level level = piece == null ? own : levelOf(own, piece.destDim());
            if (level == null || only != null && level != only) continue;
            run(new Frame(sub, piece == null ? null : piece.mapping, level, index, split, node,
                planesOf(shape, edges, split, node)), original);
        }
    }

    /** Per edge cut by the contraption and touching {@code node}: its aperture plane keeping {@code node}'s side, local frame. */
    private static IplStraddlePoseMap.LocalHalfSpace[] planesOf(IplContraptionShape shape, IplPieceMap.Edge[] edges,
                                                                IplContraptionShape.Split split, int node) {
        IplStraddlePoseMap.LocalHalfSpace[] planes = new IplStraddlePoseMap.LocalHalfSpace[edges.length];
        for (int k = 0; k < edges.length; k++) {
            IplPieceMap.Edge e = edges[k];
            if (e.parent != node && e.child != node || !split.cut().contains(e)) continue;
            planes[k] = shape.toLocal(IplStraddlePoseMap.LocalHalfSpace.keeping(e.aperture, e.parent == node));
        }
        return planes;
    }

    private static void run(Frame frame, Runnable original) {
        ACTIVE.set(frame);
        ACTIVE_COUNT.incrementAndGet();
        try {
            original.run();
        } finally {
            ACTIVE_COUNT.decrementAndGet();
            ACTIVE.remove();
        }
    }

    @Nullable
    private static Level levelOf(Level own, ResourceKey<Level> dimension) {
        if (own instanceof ServerLevel server) return server.getServer().getLevel(dimension);
        return IplClientProjections.levelOf(dimension);
    }

    @Nullable
    private static Frame frame() {
        return ACTIVE_COUNT.get() == 0 ? null : ACTIVE.get();
    }

    /** {@code sub.logicalPose()}: the pose of the piece being collided. */
    public static Pose3d logicalPose(SubLevel sub, Pose3d original) {
        Frame frame = frame();
        return frame == null || frame.mapping == null || frame.sub != sub ? original : frame.mapping.mapPose(original);
    }

    /** {@code sub.lastPose()}: the previous pose of the piece being collided. */
    public static Pose3dc lastPose(SubLevel sub, Pose3dc original) {
        Frame frame = frame();
        return frame == null || frame.mapping == null || frame.sub != sub ? original : frame.mapping.mapPose(original);
    }

    /** The level whose entities the piece being collided pushes. */
    public static Level level(Level original) {
        Frame frame = frame();
        return frame == null ? original : frame.level;
    }

    /** A piece is collided: Create must use per-block shapes, never its simplified hull. */
    public static boolean splitting() {
        return frame() != null;
    }

    /**
     * Whether contraption block {@code local} lies wholly in other pieces than the one collided.
     * Asked right before the block's shapes are read: a block lying across an aperture notes its
     * plane for {@link #cut}.
     */
    public static boolean hides(BlockPos local) {
        Frame frame = frame();
        if (frame == null) return false;
        frame.current = null;
        int i = frame.index.getInt(local);
        if (i < 0) return false;
        if (!frame.split.holds(i, frame.node)) return true;
        int edge = frame.split.acrossEdge()[i];
        if (edge >= 0) frame.current = frame.planes[edge];
        return false;
    }

    /** {@code out} keeping only the collided piece's side of the block noted by {@link #hides}. */
    public static Shapes.DoubleLineConsumer cut(Shapes.DoubleLineConsumer out) {
        Frame frame = frame();
        IplStraddlePoseMap.LocalHalfSpace plane = frame == null ? null : frame.current;
        if (plane == null) return out;
        return (minX, minY, minZ, maxX, maxY, maxZ) -> IplBoxCut.cut(minX, minY, minZ, maxX, maxY, maxZ, plane, out::consume);
    }
}
