package ipl.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
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

    static double distanceSquaredWithSubLevels(
        Level level, Vec3 playerPos, double x, double y, double z
    ) {
        return Sable.HELPER.distanceSquaredWithSubLevels(level, playerPos, x, y, z);
    }

    static boolean hasSubLevelFloorThisTick(Entity entity) {
        if (!(entity instanceof EntityMovementExtension ext)) {
            return false;
        }
        SubLevelEntityCollision.CollisionInfo info = ext.sable$getCollisionInfo();
        return info != null
            && info.trackingSubLevel != null
            && info.verticalCollisionBelow;
    }

    static boolean isFloorSubLevelStraddlingPortal(Entity entity) {
        if (!(entity instanceof EntityMovementExtension ext)) {
            return false;
        }
        SubLevelEntityCollision.CollisionInfo info = ext.sable$getCollisionInfo();
        if (info == null || info.trackingSubLevel == null || !info.verticalCollisionBelow) {
            return false;
        }
        return ipl.sable.transit.IplStraddlePoseMap.isStraddling(
            info.trackingSubLevel, entity.level());
    }

    @Nullable
    static net.minecraft.resources.ResourceKey<Level> subLevelDimensionOfVehicle(Entity vehicle) {
        if (vehicle == null) return null;
        SubLevel containing = Sable.HELPER.getContaining(vehicle);
        if (containing == null) return null;
        return containing.getLevel().dimension();
    }

    static long effectiveTrackingChunkPos(Entity entity) {
        Vec3 raw = entity.position();
        SubLevel subLevel = Sable.HELPER.getContaining(entity.level(), raw);
        if (subLevel == null) {
            return SableBridge.NO_REMAP;
        }
        // The entity's literal position is deep in the sub-level plot grid
        // (~20M blocks out). Its *visible* position -- where players actually see
        // it and therefore where tracking visibility should be evaluated -- is the
        // plot-local position mapped through the sub-level's pose. This mirrors
        // Sable's own vanilla-path remap in
        // entity_tracking.TrackedEntityMixin#sable$trackSubLevelEntities, which IP
        // strands because IP relocates the tracking loop out of vanilla ChunkMap.
        Vec3 visible = subLevel.logicalPose().transformPosition(raw);
        return ChunkPos.asLong(
            SectionPos.blockToSectionCoord(visible.x),
            SectionPos.blockToSectionCoord(visible.z)
        );
    }
}
