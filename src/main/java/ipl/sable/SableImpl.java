package ipl.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Internal implementation class for {@link SableBridge}. This file references Sable types
 * directly in its method bodies, so the JVM must be able to resolve {@code dev.ryanhcode.sable.*}
 * when its methods are verified -- which only happens when {@link SableBridge#PRESENT} is true
 * and the public bridge methods dispatch here.
 *
 * <p>Do not call into this class directly. Go through {@link SableBridge} so the presence
 * guard always runs first.
 */
final class SableImpl {

    private SableImpl() {}

    @Nullable
    static BlockState lookupNonAirSubLevelBlockAt(Level world, Vec3 worldPos) {
        BlockPos searchPos = BlockPos.containing(worldPos);
        Iterable<SubLevel> intersecting = Sable.HELPER.getAllIntersecting(world, new BoundingBox3d(searchPos));
        for (SubLevel destSub : intersecting) {
            BlockPos localPos = BlockPos.containing(
                destSub.logicalPose().transformPositionInverse(worldPos.add(0.0, 0.001, 0.0))
            );
            BlockState subState = world.getBlockState(localPos);
            if (!subState.isAir()) {
                return subState;
            }
        }
        return null;
    }

    static boolean isPlotChunk(Level world, ChunkPos chunkPos) {
        SubLevelContainer container = SubLevelContainer.getContainer(world);
        return container != null && container.inBounds(chunkPos);
    }
}
