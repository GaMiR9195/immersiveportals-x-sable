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
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entities against contraptions on straddling bodies. Create collides the entities around a
 * contraption once per tick in its own level, and Sable places that collision at the body's
 * logical pose. Here it runs once per piece the contraption is in: at the piece's pose, against
 * the entities of the piece's level and with that piece's blocks only. What went through a
 * portal pushes entities beyond it, and nothing pushes them at the entrance.
 *
 * <p>A client collides only its own player, so it runs the pieces in the player's level
 * (contraptions of other levels too, see {@code ContraptionHandlerRemoteMixin}).
 */
public final class IplContraptionCollision {

    /** One piece being collided: the pose override, the level searched and the blocks kept. */
    private record Frame(SubLevel sub, @Nullable IplStraddlePoseMap.StraddleMapping mapping, Level level,
                         Object2IntOpenHashMap<BlockPos> index, IplContraptionShape.Split split, int node) {}

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
        IplContraptionShape.Split split = shape.split(tree.edges(), tree.pieces().contraptionLabels(contraption.getUUID()));
        Object2IntOpenHashMap<BlockPos> index = new Object2IntOpenHashMap<>(shape.blocks.length);
        index.defaultReturnValue(-1);
        for (int i = 0; i < shape.blocks.length; i++) index.put(shape.blocks[i], i);
        BitSet nodes = split.nodes();
        for (int node = nodes.nextSetBit(0); node >= 0; node = nodes.nextSetBit(node + 1)) {
            IplProjectionTree.Node piece = tree.node(node);
            Level level = piece == null ? own : levelOf(own, piece.destDim());
            if (level == null || only != null && level != only) continue;
            run(new Frame(sub, piece == null ? null : piece.mapping, level, index, split, node), original);
        }
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

    /** Whether contraption block {@code local} lies wholly in other pieces than the one collided. */
    public static boolean hides(BlockPos local) {
        Frame frame = frame();
        if (frame == null) return false;
        int i = frame.index.getInt(local);
        return i >= 0 && !frame.split.holds(i, frame.node);
    }
}
