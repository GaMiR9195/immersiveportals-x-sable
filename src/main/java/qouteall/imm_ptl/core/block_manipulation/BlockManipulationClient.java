package qouteall.imm_ptl.core.block_manipulation;

import ipl.sable.SableBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.PortalUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public class BlockManipulationClient {
    private static final Minecraft client = Minecraft.getInstance();
    
    public static ResourceKey<Level> remotePointedDim;
    public static HitResult remoteHitResult;
    /**
     * Sable: the dimension through whose portal view the remote hit was SEEN, when that
     * differs from {@link #remotePointedDim} (a ship owned by one level, projected into the
     * portal's destination). The hit outline is drawn in that dimension's render pass.
     */
    @Nullable
    public static ResourceKey<Level> remoteViewDim;
    
    /**
     * The portal an entity on its far side is targeted through ({@link #remoteHitResult} is then
     * an {@link EntityHitResult} in the portal's destination). Attacks and interactions with it
     * are sent to the server together with this portal, which acts for the player on that side.
     */
    @Nullable
    public static UUID remoteEntityPortalId;
    
    /** True while a remote entity attack/interaction runs (its packets are redirected). */
    private static boolean performingRemoteEntityAction = false;
    
    public static boolean isPerformingRemoteEntityAction() {
        return performingRemoteEntityAction && remoteEntityPortalId != null && remotePointedDim != null;
    }
    
    public static boolean isPointingToPortal() {
        return remotePointedDim != null;
    }
    
    @Nullable
    public static ClientLevel getRemotePointedWorld() {
        if (remotePointedDim == null) {
            return null;
        }
        return ClientWorldLoader.getWorld(BlockManipulationClient.remotePointedDim);
    }
    
    private static BlockHitResult createMissedHitResult(Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from).normalize();
        
        return BlockHitResult.miss(to, Direction.getNearest(dir.x, dir.y, dir.z), BlockPos.containing(to));
    }
    
    private static boolean hitResultIsMissedOrNull(HitResult bhr) {
        return bhr == null || bhr.getType() == HitResult.Type.MISS;
    }
    
    public static void updatePointedBlock(float partialTick) {
        if (client.gameMode == null || client.level == null || client.player == null) {
            return;
        }
        
        remotePointedDim = null;
        remoteHitResult = null;
        remoteViewDim = null;
        remoteEntityPortalId = null;

        if (!NeoForge.EVENT_BUS.post(new BlockManipulationServer.CrossPortalInteractionEvent(client.player)).canDo()) {
            return;
        }
        
        // Picking is done from the eyes, like vanilla's pick: in third person the camera is
        // elsewhere (and may already be moved to a portal's far side by the cross-portal view).
        Vec3 eyePos = client.player.getEyePosition(partialTick);
        Vec3 viewVector = client.player.getViewVector(partialTick);
        
        double reachDistance = client.player.blockInteractionRange();
        double searchDistance = Math.max(reachDistance, client.player.entityInteractionRange());
        
        PortalUtils.raytracePortalFromEntityView(
            client.player, partialTick, searchDistance, true, p -> p.isInteractableBy(client.player)
        ).ifPresent(pair -> {
            Portal portal = pair.getFirst();
            Vec3 hitPos = pair.getSecond().hitPos();
            double distanceToPortalPointing = hitPos.distanceTo(eyePos);
            if (distanceToPortalPointing >= getCurrentTargetDistance(eyePos) + 0.2) {
                return;
            }
            // The portal is in front of whatever vanilla picked locally (block or entity).
            client.hitResult = createMissedHitResult(eyePos, hitPos);
            client.crosshairPickEntity = null;
            
            if (distanceToPortalPointing < reachDistance) {
                updateTargetedBlockThroughPortal(eyePos, viewVector, distanceToPortalPointing, reachDistance, portal);
            }
            updateTargetedEntityThroughPortal(eyePos, viewVector, distanceToPortalPointing, portal);
        });
        
        if (SableBridge.PRESENT) {
            retargetShipHit();
        }
    }
    
    /**
     * Sable ships straddling portals. A hit on a ship comes back in the ship's PLOT
     * coordinates, and those blocks live in the level that OWNS the ship -- not necessarily
     * the world the hit was found in: a cross-dimension projection is picked in the
     * player's own level (or in a portal's destination). Interactions must run in the
     * owner level, so IP's remote-interaction machinery is pointed at it (switched client
     * world for prediction, packets redirected to the owner dimension).
     *
     * <p>A ship owned by the player's own level goes down the vanilla path even when its
     * piece was seen through a portal: its native-frame position is exactly the position
     * seen through the portal, unfolded. The outline stays on the hit either way; the
     * piece-aware outline renderer draws it only in the pass where that piece lives.
     */
    private static void retargetShipHit() {
        HitResult target = remotePointedDim != null ? remoteHitResult : client.hitResult;
        if (!(target instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        ResourceKey<Level> owner = SableBridge.clientPlotOwnerDimension(blockHit.getBlockPos());
        if (owner == null || owner == remotePointedDim) {
            return;
        }
        if (owner == client.level.dimension()) {
            // Seen through a portal, owned here: vanilla interaction on the real block.
            client.hitResult = blockHit;
            remotePointedDim = null;
            remoteViewDim = null;
            return;
        }
        // Owned by another level: remote interaction there. Through a portal the outline
        // belongs to the portal's pass; locally it stays on client.hitResult.
        remoteViewDim = remotePointedDim;
        remotePointedDim = owner;
        remoteHitResult = blockHit;
        if (remoteViewDim == null) {
            client.hitResult = blockHit;
        }
    }
    
    /**
     * Entities on the far side of the portal the player looks through: the same pick as vanilla
     * (pick radius, entity interaction range measured along the look ray in the player's own
     * space, nearest hit wins, a block in front of the entity wins over it), run on the
     * destination's entities along the transformed ray.
     */
    private static void updateTargetedEntityThroughPortal(
        Vec3 eyePos, Vec3 viewVector, double beginDistance, Portal portal
    ) {
        double entityRange = client.player.entityInteractionRange();
        if (beginDistance >= entityRange) return;
        ClientLevel world = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        if (world == null) return;
        
        Vec3 from = portal.transformPoint(eyePos.add(viewVector.scale(beginDistance)));
        Vec3 to = portal.transformPoint(eyePos.add(viewVector.scale(entityRange)));
        double maxDistSq = from.distanceToSqr(to);
        if (remotePointedDim != null && !hitResultIsMissedOrNull(remoteHitResult)) {
            // a block in front of the entity wins
            maxDistSq = Math.min(maxDistSq, SableBridge.frameAwareDistanceSqr(
                world, from, remoteHitResult.getLocation()));
        }
        
        Entity cameraEntity = client.getCameraEntity();
        EntityHitResult best = null;
        double bestDistSq = maxDistSq;
        AABB area = new AABB(from, to).inflate(1.0);
        for (Entity entity : world.getEntities(client.player, area, e ->
            !e.isSpectator() && e.isPickable() && !(e instanceof Portal) && e != cameraEntity
        )) {
            AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
            if (box.contains(from)) {
                if (bestDistSq >= 0) {
                    best = new EntityHitResult(entity, from);
                    bestDistSq = 0;
                }
                continue;
            }
            Optional<Vec3> hit = box.clip(from, to);
            if (hit.isEmpty()) continue;
            double distSq = from.distanceToSqr(hit.get());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = new EntityHitResult(entity, hit.get());
            }
        }
        if (best == null) return;
        
        remoteHitResult = best;
        remotePointedDim = portal.getDestDim();
        remoteViewDim = null;
        remoteEntityPortalId = portal.getUUID();
        client.crosshairPickEntity = best.getEntity();
    }
    
    private static double getCurrentTargetDistance(Vec3 eyePos) {
        if (hitResultIsMissedOrNull(client.hitResult)) {
            return 23333;
        }
        
        if (client.hitResult instanceof BlockHitResult) {
            BlockPos hitPos = ((BlockHitResult) client.hitResult).getBlockPos();
            if (client.level.getBlockState(hitPos).getBlock() == PortalPlaceholderBlock.instance) {
                return 23333;
            }
        }
        
        // Frame-aware: a local hit on a Sable sub-level is in PLOT coordinates; the raw
        // distance (~20M) would let the portal path wrongly steal priority from it.
        return Math.sqrt(SableBridge.frameAwareDistanceSqr(
            client.level, eyePos, client.hitResult.getLocation()));
    }
    
    private static void updateTargetedBlockThroughPortal(
        Vec3 eyePos,
        Vec3 viewVector,
        double beginDistance,
        double endDistance,
        Portal portal
    ) {
        Vec3 from = portal.transformPoint(eyePos.add(viewVector.scale(beginDistance)));
        Vec3 to = portal.transformPoint(eyePos.add(viewVector.scale(endDistance)));
        
        ClipContext context = new ClipContext(
            from,
            to,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            client.player
        );
        
        ClientLevel world = ClientWorldLoader.getWorld(portal.getDestDim());
        
        remoteHitResult = BlockGetter.traverseBlocks(
            from, to,
            context,
            (rayTraceContext, blockPos) -> {
                BlockState blockState = world.getBlockState(blockPos);
                
                if (blockState.getBlock() == PortalPlaceholderBlock.instance) {
                    return null;
                }
                if (blockState.getBlock() == Blocks.BARRIER) {
                    return null;
                }
                
                FluidState fluidState = world.getFluidState(blockPos);
                Vec3 start = rayTraceContext.getFrom();
                Vec3 end = rayTraceContext.getTo();
                
                // moved back a little so the start is not considered inside the block
                Vec3 correctedStart = start.subtract(end.subtract(start).scale(0.0015));
                VoxelShape solidShape = rayTraceContext.getBlockShape(blockState, world, blockPos);
                BlockHitResult blockHitResult = world.clipWithInteractionOverride(
                    correctedStart, end, blockPos, solidShape, blockState
                );
                VoxelShape fluidShape = rayTraceContext.getFluidShape(fluidState, world, blockPos);
                BlockHitResult fluidHitResult = fluidShape.clip(start, end, blockPos);
                double d = blockHitResult == null ? Double.MAX_VALUE :
                    rayTraceContext.getFrom().distanceToSqr(blockHitResult.getLocation());
                double e = fluidHitResult == null ? Double.MAX_VALUE :
                    rayTraceContext.getFrom().distanceToSqr(fluidHitResult.getLocation());
                return d <= e ? blockHitResult : fluidHitResult;
            },
            (rayTraceContext) -> {
                Vec3 vec3d = rayTraceContext.getFrom().subtract(rayTraceContext.getTo());
                return BlockHitResult.miss(
                    rayTraceContext.getTo(),
                    Direction.getNearest(vec3d.x, vec3d.y, vec3d.z),
                    BlockPos.containing(rayTraceContext.getTo())
                );
            }
        );
        
        // Sable ships: the traverseBlocks lambda above bypasses level.clip, so sub-levels
        // (native ones in the dest world AND foreign straddle projections into it) are
        // invisible to it. Run the overlay-aware clip too; if it hits a ship (plot-coord
        // result) that is frame-closer than the terrain hit, prefer it.
        if (SableBridge.PRESENT) {
            BlockHitResult overlayHit = world.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (overlayHit != null && overlayHit.getType() == HitResult.Type.BLOCK
                && (Math.abs(overlayHit.getBlockPos().getX()) >= 1_000_000
                    || Math.abs(overlayHit.getBlockPos().getZ()) >= 1_000_000)) {
                double terrainDistSq = hitResultIsMissedOrNull(remoteHitResult)
                    ? Double.MAX_VALUE
                    : from.distanceToSqr(remoteHitResult.getLocation());
                double shipDistSq = SableBridge.frameAwareDistanceSqr(
                    world, from, overlayHit.getLocation());
                if (shipDistSq < terrainDistSq) {
                    remoteHitResult = overlayHit;
                }
            }
        }

        if (remoteHitResult.getLocation().y < world.getMinBuildHeight() + 0.1) {
            remoteHitResult = new BlockHitResult(
                remoteHitResult.getLocation(),
                Direction.DOWN,
                ((BlockHitResult) remoteHitResult).getBlockPos(),
                ((BlockHitResult) remoteHitResult).isInside()
            );
        }
        
        if (remoteHitResult != null) {
            if (!world.getBlockState(((BlockHitResult) remoteHitResult).getBlockPos()).isAir()) {
                client.hitResult = createMissedHitResult(from, to);
                remotePointedDim = portal.getDestDim();
            }
        }
        
    }
    
    /**
     * It will not switch the dimension of client player
     */
    public static <T> T withSwitchedContext(
        Supplier<T> func, boolean transformHitResult
    ) {
        Validate.notNull(remoteHitResult);
        
        ClientLevel remoteWorld = getRemotePointedWorld();
        Validate.notNull(remoteWorld);
        
        HitResult effectiveHitResult;
        
        if (transformHitResult && (remoteHitResult instanceof BlockHitResult blockHitResult)) {
            Tuple<BlockHitResult, ResourceKey<Level>> r =
                BlockManipulationServer.getHitResultForPlacing(remoteWorld, blockHitResult);
            effectiveHitResult = r.getA();
            remoteWorld = ClientWorldLoader.getWorld(r.getB());
            Validate.notNull(remoteWorld);
            Validate.notNull(effectiveHitResult);
        }
        else {
            effectiveHitResult = remoteHitResult;
        }
        
        boolean entityAction = effectiveHitResult instanceof EntityHitResult && remoteEntityPortalId != null;
        T result = ClientWorldLoader.withSwitchedWorld(
            remoteWorld, () -> {
                HitResult originalHitResult = client.hitResult;
                boolean originalFlag = performingRemoteEntityAction;
                client.hitResult = effectiveHitResult;
                performingRemoteEntityAction = entityAction;
                try {
                    return func.get();
                }
                finally {
                    client.hitResult = originalHitResult;
                    performingRemoteEntityAction = originalFlag;
                }
            }
        );
        if (entityAction && client.player != null && client.player.getVehicle() != null
            && client.player.getVehicle().level() != client.player.level()
        ) {
            // never ride something in another world through a portal
            client.player.stopRiding();
        }
        return result;
    }

    @Nullable
    public static String getDebugString() {
        if (remotePointedDim == null) {
            return null;
        }
        if (remoteHitResult instanceof BlockHitResult blockHitResult) {
            return "Point:%s %d %d %d".formatted(
                remotePointedDim.location(),
                blockHitResult.getBlockPos().getX(),
                blockHitResult.getBlockPos().getY(),
                blockHitResult.getBlockPos().getZ()
            );
        }
        else {
            return null;
        }
    }
}
