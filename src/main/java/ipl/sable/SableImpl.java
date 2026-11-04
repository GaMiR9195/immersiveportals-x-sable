package ipl.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import ipl.sable.atlas.IplScenes;
import ipl.sable.client.IplClientProjections;
import ipl.sable.transit.IplProjectionEntities;
import ipl.sable.transit.IplProjectionTree;
import ipl.sable.transit.IplShipPortalAnchor;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

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

    static double frameAwareDistanceSqr(Level level, Vec3 a, Vec3 b) {
        return Sable.HELPER.distanceSquaredWithSubLevels(level, a, b);
    }

    static boolean isFloorSubLevelStraddlingPortal(Entity entity) {
        if (!(entity instanceof EntityMovementExtension ext)) {
            return false;
        }
        SubLevelEntityCollision.CollisionInfo info = ext.sable$getCollisionInfo();
        if (info == null || info.trackingSubLevel == null || !info.verticalCollisionBelow) {
            return false;
        }
        return IplStraddlePoseMap.isStraddling(
            info.trackingSubLevel, entity.level());
    }

    @Nullable
    static ResourceKey<Level> subLevelDimensionOfVehicle(Entity vehicle) {
        if (vehicle == null) return null;
        SubLevel containing = Sable.HELPER.getContaining(vehicle);
        if (containing == null) return null;
        return containing.getLevel().dimension();
    }

    /**
     * The level that owns the block at plot position {@code pos}: the owning ship's
     * level (a projected ship's plot may be read from another dimension), else
     * {@code context}.
     */
    static ServerLevel plotOwnerLevel(
        ServerLevel context, BlockPos pos
    ) {
        ServerSubLevel sub = subLevelAtPlotPos(context, pos);
        return sub != null ? sub.getLevel() : context;
    }

    static boolean isProjectedBlockWithinReach(
        ServerPlayer player,
        ResourceKey<Level> dimension, BlockPos pos, double distanceSquare
    ) {
        ServerLevel owner = player.server.getLevel(dimension);
        if (owner == null) return false;
        ServerSubLevel sub = subLevelAtPlotPos(owner, pos);
        if (sub == null || sub.getLevel() != owner) return false;
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 at = player.position();
        boolean[] ok = {false};
        // Every frame through which the ship exists in the player's level (its native frame
        // when native there, plus every portal image into it, chains included).
        IplProjectionTree.forEachFrameIn(sub, player.level(), node -> {
            if (ok[0]) return;
            Vec3 world = IplProjectionEntities.framePose(sub, node).transformPosition(center);
            if (world.distanceToSqr(at) < distanceSquare) ok[0] = true;
        });
        return ok[0];
    }

    @Nullable
    static ResourceKey<Level> clientPlotOwnerDimension(BlockPos pos) {
        SubLevel sub = IplClientProjections.findSubLevelAtPlot(Vec3.atCenterOf(pos));
        Level level = sub == null ? null : sub.getLevel();
        return level == null ? null : level.dimension();
    }

    /** Whether {@code pos} lies in a live ship plot (own grid first, then other levels). */
    static boolean isPlotPos(ServerLevel context, BlockPos pos) {
        return subLevelAtPlotPos(context, pos) != null;
    }

    /**
     * The live ServerSubLevel owning the plot at {@code plotPos}, or null. The context
     * level's own grid wins; plot slots are allocated server-wide unique
     * ({@code SubLevelContainerMixin}), so the cross-level fallback is unambiguous.
     */
    @Nullable
    static ServerSubLevel subLevelAtPlotPos(
        ServerLevel any, BlockPos plotPos
    ) {
        return IplScenes.plotOwner(any, plotPos);
    }

    static ServerLevel effectivePortalGenLevel(
        ServerLevel world, BlockPos pos
    ) {
        ServerSubLevel sub = subLevelAtPlotPos(world, pos);
        return sub != null ? sub.getLevel() : world;
    }

    @Nullable
    static Vec3 shipFrameWorldCenter(ServerLevel world, BlockPos pos) {
        ServerSubLevel sub = subLevelAtPlotPos(world, pos);
        if (sub == null) return null;
        return sub.logicalPose().transformPosition(pos.getCenter());
    }

    static void mapShipFramePortalPose(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return;
        BlockPos plotPos = BlockPos.containing(portal.getOriginPos());
        ServerSubLevel sub = subLevelAtPlotPos(level, plotPos);
        if (sub == null) return;

        Pose3dc pose = sub.logicalPose();
        Quaterniondc shipRot = pose.orientation();
        DQuaternion shipD =
            new DQuaternion(
                shipRot.x(), shipRot.y(), shipRot.z(), shipRot.w());

        DQuaternion o0 = portal.getOrientationRotation();
        DQuaternion r0 = portal.getRotation() == null
            ? DQuaternion.identity : portal.getRotation();
        // D₀ = R₀ ∘ O₀ is the dest frame's orientation; keep it fixed while the
        // origin frame picks up the ship rotation (M6 dest-lock math).
        DQuaternion destLock = r0.hamiltonProduct(o0);
        DQuaternion oNow = shipD.hamiltonProduct(o0);

        portal.setOriginPos(pose.transformPosition(portal.getOriginPos()));
        portal.setOrientationRotation(oNow);
        portal.setRotation(destLock.hamiltonProduct(oNow.getConjugated()));
    }

    static void anchorShipFramePortal(
        Portal portal, BlockPos shapeAnchor
    ) {
        if (!(portal.level() instanceof ServerLevel level)) return;
        ServerSubLevel sub = subLevelAtPlotPos(level, shapeAnchor);
        if (sub == null) return;
        IplShipPortalAnchor.anchorToShip(portal, sub);
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
