package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.atlas.IplFusedStep;
import ipl.sable.mixin.MergedMassTrackerAccessor;
import ipl.sable.mixin.SubLevelAccessor;
import ipl.sable.mixin.SubLevelContainerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "flip": the real body takes over the far side once it has fully passed a portal.
 *
 * <ul>
 *   <li><b>Same dimension</b>: pose teleport of the live body through the portal
 *       isometry (the body, its joints and its plot persist). Clients rebase their
 *       interpolation buffers through the same isometry ({@code handoff} RPC), so the
 *       jump is invisible.</li>
 *   <li><b>Other dimension</b>: the sub-level is moved into the destination level
 *       (Sable's native model: a sub-level lives in exactly one level). The copy keeps
 *       the same global plot slot when free, so plot-local coordinates stay valid.
 *       Clients keep rendering the old instance as a projection (a "ghost") until the
 *       new instance is visible ({@code move} RPC).</li>
 * </ul>
 */
public final class IplShipFlip {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-flip");

    private IplShipFlip() {}

    public static boolean canFlip(ServerSubLevel ship, Portal portal) {
        if (ship == null || ship.isRemoved() || portal == null || portal.isRemoved()
            || !portal.isTeleportable() || Math.abs(portal.getScaling() - 1.0) > 1e-9) return false;
        if (portal.level() != ship.getLevel()) return false;
        MinecraftServer server = ship.getLevel().getServer();
        ServerLevel destination = server == null ? null : server.getLevel(portal.getDestDim());
        if (destination == null) return false;
        ServerSubLevelContainer source = SubLevelContainer.getContainer(ship.getLevel());
        ServerSubLevelContainer target = SubLevelContainer.getContainer(destination);
        return source != null && target != null
            && source.physicsSystem().getPipeline() != null
            && target.physicsSystem().getPipeline() != null;
    }

    public static boolean isCrossDimension(ServerSubLevel ship, Portal portal) {
        return !portal.getDestDim().equals(ship.getLevel().dimension());
    }

    /** Executes the flip; returns the live body afterwards (a new instance when moved), or null. */
    @Nullable
    public static ServerSubLevel flip(ServerSubLevel ship, Portal portal, Map<UUID, UUID> movedRiders) {
        if (!canFlip(ship, portal)) return null;
        return isCrossDimension(ship, portal)
            ? moveAcross(ship, portal, movedRiders)
            : teleportWithin(ship, portal, movedRiders);
    }

    // ------------------------------------------------------------------ same dimension

    private static ServerSubLevel teleportWithin(ServerSubLevel ship, Portal portal, Map<UUID, UUID> movedRiders) {
        ServerLevel level = ship.getLevel();
        MinecraftServer server = level.getServer();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        UUID uuid = ship.getUniqueId();
        Pose3d mappedPrevious = IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(ship.lastPose());
        Pose3d mappedPose = IplTransitOps.computeMappedPose(new Pose3d(ship.logicalPose()), portal);
        Vector3d linear = pipeline.getLinearVelocity(ship, new Vector3d());
        Vector3d angular = pipeline.getAngularVelocity(ship, new Vector3d());
        Vec3 mappedLin = portal.transformLocalVec(new Vec3(linear.x, linear.y, linear.z));
        Vec3 mappedAng = portal.transformLocalVec(new Vec3(angular.x, angular.y, angular.z));
        teleportRiders(ship, level, level, portal, movedRiders);
        pipeline.teleport(ship, mappedPose.position(), mappedPose.orientation());
        ship.logicalPose().set(mappedPose);
        ((SubLevelAccessor) ship).ipl$getPreviousPose().set(mappedPrevious);
        pipeline.resetVelocity(ship);
        pipeline.addLinearAndAngularVelocity(ship,
            new Vector3d(mappedLin.x, mappedLin.y, mappedLin.z),
            new Vector3d(mappedAng.x, mappedAng.y, mappedAng.z));
        endSessions(server, uuid, "flip");
        IplStraddleSessionSync.advanceFrame(uuid, portal);
        ship.updateBoundingBox();
        PENDING_HANDOFFS.add(new PendingHandoff(ship, encodePortalTransform(portal),
            IplStraddleSessionSync.encodePortal(portal), container.trackingSystem().getInterpolationTick(),
            IplStraddleSessionSync.frame(uuid), IplGrabLink.inverse(portal).encode()));
        LOG.debug("[IPL-FLIP] {} teleported through {}", uuid, portal.getUUID());
        return ship;
    }

    // ------------------------------------------------------------------ other dimension

    @Nullable
    private static ServerSubLevel moveAcross(ServerSubLevel ship, Portal portal, Map<UUID, UUID> movedRiders) {
        ServerLevel sourceLevel = ship.getLevel();
        MinecraftServer server = sourceLevel.getServer();
        ServerLevel destination = server.getLevel(portal.getDestDim());
        ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(sourceLevel);
        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destination);
        int[] slot = sameSlot(ship, destContainer);
        if (slot == null) slot = IplTransitOps.findFirstEmptyPlot(destContainer);
        if (slot == null) {
            LOG.warn("[IPL-MOVE] destination {} plot grid full; {} stays projected",
                destination.dimension().location(), ship.getUniqueId());
            return null;
        }
        ChunkPos srcMin = ship.getPlot().getChunkMin();
        int logPlot = destContainer.getLogPlotSize();
        var origin = (SubLevelContainerAccessor) destContainer;
        double slotDeltaX = (double) (((slot[0] + origin.ipl$originX()) << logPlot) - srcMin.x) * 16.0;
        double slotDeltaZ = (double) (((slot[1] + origin.ipl$originZ()) << logPlot) - srcMin.z) * 16.0;
        UUID uuid = ship.getUniqueId();
        Pose3d pose = IplTransitOps.computeMappedPose(new Pose3d(ship.logicalPose()), portal);
        // The previous pose crosses too: with lastPose == pose the client's first interval of the
        // successor stands still and Sable does not carry entities on the hull for a tick.
        Pose3d previous = IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(ship.lastPose());
        if (slotDeltaX != 0.0 || slotDeltaZ != 0.0) {
            pose.rotationPoint().add(slotDeltaX, 0.0, slotDeltaZ);
            previous.rotationPoint().add(slotDeltaX, 0.0, slotDeltaZ);
            LOG.warn("[IPL-MOVE] same plot slot unavailable for {}; plot-local references may go stale", uuid);
        }
        // The body already made this tick's step in the source. If the destination still steps
        // this tick (stock natives, a level that ticks later), the successor starts from the
        // previous pose and that step brings it back to where it left the source; otherwise it
        // starts at the pose itself. Under the fused world every level has stepped by now.
        Pose3d start = IplFusedStep.stepsLaterThisTick(destContainer.physicsSystem()) ? new Pose3d(previous) : pose;
        PhysicsPipeline sourcePipeline = sourceContainer.physicsSystem().getPipeline();
        Vector3d linear = sourcePipeline.getLinearVelocity(ship, new Vector3d());
        Vector3d angular = sourcePipeline.getAngularVelocity(ship, new Vector3d());
        Vec3 mappedLin = portal.transformLocalVec(new Vec3(linear.x, linear.y, linear.z));
        Vec3 mappedAng = portal.transformLocalVec(new Vec3(angular.x, angular.y, angular.z));

        // Frame boundary on the shared timeline, as for a same-dimension handoff: this tick's
        // snapshot (the pose that completed the crossing) is still the old frame.
        int firstDestinationTick = sourceContainer.trackingSystem().getInterpolationTick();
        endSessions(server, uuid, "move");
        IplStraddleSessionSync.advanceFrame(uuid, portal);
        // The ghost bridge must reach clients BEFORE Sable's stop-tracking packet.
        sendMove(server, ship, sourceLevel, destination, portal, pose, firstDestinationTick);
        teleportRiders(ship, sourceLevel, destination, portal, movedRiders);

        ServerSubLevel moved;
        try {
            moved = (ServerSubLevel) destContainer.allocateSubLevel(uuid, slot[0], slot[1], start);
        } catch (RuntimeException e) {
            LOG.error("[IPL-MOVE] allocation in {} failed for {}", destination.dimension().location(), uuid, e);
            return null;
        }
        try {
            ((SubLevelAccessor) moved).ipl$getPreviousPose().set(previous);
            if (ship.getName() != null) moved.setName(ship.getName());
            if (ship.getUserDataTag() != null) moved.setUserDataTag(ship.getUserDataTag().copy());
            seedMassBaseline(ship, moved, slotDeltaX, slotDeltaZ);
            IplTransitOps.copyPlotBlocks(ship.getPlot(), moved.getPlot(), sourceLevel, destination);
            relocatePlotEntities(ship, moved, sourceLevel, destination);
            if (mappedLin.lengthSqr() > 1e-12 || mappedAng.lengthSqr() > 1e-12) {
                destContainer.physicsSystem().getPipeline().addLinearAndAngularVelocity(moved,
                    new Vector3d(mappedLin.x, mappedLin.y, mappedLin.z),
                    new Vector3d(mappedAng.x, mappedAng.y, mappedAng.z));
            }
            // Staff joints on the source body go while it is still valid; the sessions stay.
            IplGrabChain.beforeCrossMove(ship);
            sourceContainer.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
        } catch (RuntimeException e) {
            LOG.error("[IPL-MOVE] move of {} failed; rolling back the copy", uuid, e);
            try {
                if (!moved.isRemoved()) destContainer.removeSubLevel(moved, SubLevelRemovalReason.REMOVED);
            } catch (RuntimeException rollbackError) {
                LOG.error("[IPL-MOVE] rollback failed for {}", uuid, rollbackError);
            }
            return null;
        }
        try {
            // Active staff drags continue on the successor (see IplGrabChain.afterCrossMove).
            IplGrabChain.afterCrossMove(ship, moved, new Vector3d(slotDeltaX, 0.0, slotDeltaZ));
        } catch (RuntimeException e) {
            LOG.error("[IPL-MOVE] staff sessions of {} could not follow the move", uuid, e);
        }
        LOG.debug("[IPL-MOVE] {} moved {} -> {}", uuid, sourceLevel.dimension().location(),
            destination.dimension().location());
        return moved;
    }

    /** Same global plot slot in the destination grid, when it is free. */
    private static int @Nullable [] sameSlot(ServerSubLevel ship, ServerSubLevelContainer dest) {
        ChunkPos center = ship.getPlot().getCenterChunk();
        int logPlot = dest.getLogPlotSize();
        var origin = (SubLevelContainerAccessor) dest;
        int localX = (center.x >> logPlot) - origin.ipl$originX();
        int localZ = (center.z >> logPlot) - origin.ipl$originZ();
        int side = 1 << dest.getLogSideLength();
        if (localX < 0 || localX >= side || localZ < 0 || localZ >= side) return null;
        if (dest.getOccupancy().get(dest.getIndex(localX, localZ))) return null;
        return new int[]{localX, localZ};
    }

    private static void endSessions(MinecraftServer server, UUID uuid, String reason) {
        IplAtlasStraddleSession.clearShip(uuid, reason);
        // No snapshot for the old frame: clients drop its sessions when they enter the new frame,
        // whose snapshot (the promoted sessions) and labels follow the flip.
        IplStraddleSessionSync.forgetAllFor(uuid);
    }

    /** See {@link MergedMassTrackerAccessor}: the copy must not re-base the rotation point. */
    private static void seedMassBaseline(ServerSubLevel source, ServerSubLevel target, double dx, double dz) {
        if (!(source.getMassTracker() instanceof MergedMassTracker src)
            || !(target.getMassTracker() instanceof MergedMassTracker dst)) return;
        Vector3dc com = src.getCenterOfMass();
        if (com == null) return;
        MergedMassTrackerAccessor access = (MergedMassTrackerAccessor) (Object) dst;
        access.ipl$setLastCenterOfMass(new Vector3d(com).add(dx, 0.0, dz));
        access.ipl$setLastInertiaTensor(new Matrix3d(src.getInertiaTensor()));
        access.ipl$setLastMass(src.getMass());
    }

    private static void relocatePlotEntities(ServerSubLevel source, ServerSubLevel target,
                                             ServerLevel from, ServerLevel to) {
        ServerLevelPlot srcPlot = source.getPlot();
        ServerLevelPlot dstPlot = target.getPlot();
        for (PlotChunkHolder holder : new ArrayList<>(srcPlot.getLoadedChunks())) {
            ChunkPos srcChunk = holder.getChunk().getPos();
            ChunkPos dstChunk = dstPlot.toGlobal(srcPlot.toLocal(srcChunk));
            double dx = dstChunk.getMinBlockX() - srcChunk.getMinBlockX();
            double dz = dstChunk.getMinBlockZ() - srcChunk.getMinBlockZ();
            AABB box = new AABB(srcChunk.getMinBlockX(), from.getMinBuildHeight(), srcChunk.getMinBlockZ(),
                srcChunk.getMinBlockX() + 16.0, from.getMaxBuildHeight(), srcChunk.getMinBlockZ() + 16.0);
            for (Entity entity : from.getEntitiesOfClass(Entity.class, box)) {
                if (entity instanceof Portal || entity instanceof ServerPlayer || entity.isRemoved()
                    || entity.getVehicle() != null) continue;
                try {
                    Entity result = ServerTeleportationManager.teleportEntityGeneral(
                        entity, entity.position().add(dx, 0.0, dz), to);
                    if (result != null && !result.isRemoved()) result.setDeltaMovement(entity.getDeltaMovement());
                } catch (RuntimeException e) {
                    LOG.warn("[IPL-MOVE] failed to relocate plot entity {} of {}", entity, source.getUniqueId(), e);
                }
            }
        }
    }

    /**
     * Entities riding the hull that are already beyond the portal plane follow the body.
     * {@code movedRiders} maps rider -> ship that carried it this tick: a ship may pass several
     * portals in one tick (a row of portals) and its riders go along, but another ship must not
     * drag them.
     */
    private static void teleportRiders(ServerSubLevel ship, ServerLevel from, ServerLevel to, Portal portal,
                                       Map<UUID, UUID> movedRiders) {
        AABB query = ship.boundingBox().toMojang().inflate(0.5);
        Vec3 planePoint = portal.getOriginPos();
        Vec3 sourceToDest = portal.getNormal().scale(-1.0);
        UUID carrier = ship.getUniqueId();
        for (Entity entity : from.getEntitiesOfClass(Entity.class, query)) {
            UUID mover = movedRiders.get(entity.getUUID());
            if (entity instanceof Portal || entity.isRemoved() || (mover != null && !mover.equals(carrier))
                || entity.getVehicle() != null) continue;
            if (entity.getBoundingBox().getCenter().subtract(planePoint).dot(sourceToDest) < 0.0) continue;
            Vec3 mappedPos = portal.transformPoint(entity.position());
            Vec3 mappedDelta = portal.transformLocalVec(entity.getDeltaMovement());
            try {
                Entity result = ServerTeleportationManager.teleportEntityGeneral(entity, mappedPos, to);
                if (result != null && !result.isRemoved()) {
                    result.setDeltaMovement(mappedDelta);
                    movedRiders.put(entity.getUUID(), carrier);
                    movedRiders.put(result.getUUID(), carrier);
                }
            } catch (RuntimeException e) {
                LOG.warn("[IPL-FLIP] failed to carry rider {} of {}", entity, ship.getUniqueId(), e);
            }
        }
    }

    // ------------------------------------------------------------------ client sync

    private record PendingHandoff(ServerSubLevel body, String transform, String portalNbt,
                                  int firstDestinationTick, long destinationFrame, String inverseGrabLink) {}

    private static final List<PendingHandoff> PENDING_HANDOFFS = new ArrayList<>();

    /** Sends queued same-dimension handoffs (after the tracking system emitted this tick). */
    public static void flushHandoffs(MinecraftServer server) {
        if (server == null || PENDING_HANDOFFS.isEmpty()) return;
        List<PendingHandoff> batch = new ArrayList<>(PENDING_HANDOFFS);
        PENDING_HANDOFFS.clear();
        for (PendingHandoff pending : batch) {
            ServerSubLevel body = pending.body();
            if (body == null || body.isRemoved()) continue;
            for (ServerPlayer player : recipients(server, body, body.getLevel(), body.logicalPose().position())) {
                McRemoteProcedureCall.tellClientToInvoke(player,
                    "ipl.sable.client.IplParentDimSync.RemoteCallables.handoff",
                    body.getUniqueId().toString(), body.getLevel().dimension().location().toString(),
                    pending.transform(), pending.portalNbt(), Integer.toString(pending.firstDestinationTick()),
                    Long.toString(pending.destinationFrame()), pending.inverseGrabLink());
            }
        }
    }

    public static void clearPending() {
        PENDING_HANDOFFS.clear();
    }

    private static void sendMove(MinecraftServer server, ServerSubLevel ship, ServerLevel from, ServerLevel to,
                                 Portal portal, Pose3d destinationPose, int firstDestinationTick) {
        Set<ServerPlayer> players = new HashSet<>(recipients(server, ship, to, destinationPose.position()));
        for (UUID id : ship.getTrackingPlayers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) players.add(player);
        }
        String transform = encodePortalTransform(portal);
        String nbt = IplStraddleSessionSync.encodePortal(portal);
        String frame = Long.toString(IplStraddleSessionSync.frame(ship.getUniqueId()));
        String inverse = IplGrabLink.inverse(portal).encode();
        for (ServerPlayer player : players) {
            McRemoteProcedureCall.tellClientToInvoke(player,
                "ipl.sable.client.IplParentDimSync.RemoteCallables.move",
                ship.getUniqueId().toString(), from.dimension().location().toString(),
                to.dimension().location().toString(), transform, nbt, Integer.toString(firstDestinationTick),
                frame, inverse);
        }
    }

    private static List<ServerPlayer> recipients(MinecraftServer server, ServerSubLevel body,
                                                 ServerLevel viewedLevel, Vector3dc position) {
        Set<UUID> ids = new HashSet<>(body.getTrackingPlayers());
        ids.addAll(IplGrabChain.getDraggingPlayers(body.getUniqueId()));
        ChunkPos chunk = new ChunkPos(BlockPos.containing(position.x(), position.y(), position.z()));
        for (ServerPlayer viewer : ImmPtlChunkTracking.getPlayersViewingChunk(
            viewedLevel.dimension(), chunk.x, chunk.z, false)) {
            ids.add(viewer.getUUID());
        }
        List<ServerPlayer> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) out.add(player);
        }
        return out;
    }

    static String encodePortalTransform(Portal portal) {
        Vec3 origin = portal.getOriginPos();
        Vec3 destination = portal.getDestPos();
        DQuaternion rotation = portal.getRotationD();
        return String.join(";",
            Double.toHexString(origin.x), Double.toHexString(origin.y), Double.toHexString(origin.z),
            Double.toHexString(destination.x), Double.toHexString(destination.y), Double.toHexString(destination.z),
            Double.toHexString(rotation.x), Double.toHexString(rotation.y),
            Double.toHexString(rotation.z), Double.toHexString(rotation.w),
            Double.toHexString(portal.getScaling()),
            Double.toHexString(portal.getNormal().x), Double.toHexString(portal.getNormal().y),
            Double.toHexString(portal.getNormal().z),
            portal.getUUID().toString());
    }
}
