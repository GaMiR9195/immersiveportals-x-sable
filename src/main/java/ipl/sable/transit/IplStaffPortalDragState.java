package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplStaffDragSessionControl;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.mixin.IplStaffServerHandlerAccessorMixin;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Continuous coordinate frame of an active staff grab. Simulated sends a point relative to the
 * player each update, but the Rapier body lives in its sub-level parent frame. When either side
 * crosses a portal, this converts only the desired anchor point, never the body or its velocity.
 */
public final class IplStaffPortalDragState {

    private static final Map<UUID, Frame> FRAMES = new HashMap<>();

    /** Small dead band keeps same-dimension portal contact from flipping frames every substep. */
    private static final double PORTAL_SIDE_EPSILON = 0.05;

    private IplStaffPortalDragState() {}

    public static void begin(ServerPlayer player, UUID subId, org.joml.Vector3dc localAnchor) {
        Frame existing = FRAMES.get(player.getUUID());
        if (existing == null || !existing.subId.equals(subId)) {
            FRAMES.put(player.getUUID(), new Frame(subId, player.getUUID(), player.level().dimension()));
        }
    }

    public static void end(UUID playerId) {
        FRAMES.remove(playerId);
    }

    /** Players whose active staff session owns this body; transit must hand their client pose off. */
    public static Set<UUID> getDraggingPlayers(UUID subId) {
        Set<UUID> players = new HashSet<>();
        for (Map.Entry<UUID, Frame> entry : FRAMES.entrySet()) {
            if (entry.getValue().subId.equals(subId)) {
                players.add(entry.getKey());
            }
        }
        return players;
    }

    /**
     * Body parent changed. From this point raw staff goals still originate in the player's
     * current world, so map them exactly once only while that world remains portal source.
     */
    public static void onTransitCompleted(UUID subId, Portal portal) {
        for (Frame frame : FRAMES.values()) {
            if (!frame.subId.equals(subId)) continue;
            frame.portalId = portal.getUUID();
            frame.sourceDim = portal.level().dimension();
            frame.targetDim = portal.getDestDim();
            frame.completedTransit = true;
            frame.playerUsesSourceFrame = null;
            reframeLiveSession(portal, frame.playerId);
        }
    }

    /**
     * Convert player-frame motor target into the grabbed body's current parent frame. A frame is
     * discovered when needed, then held by portal UUID rather than current view direction. This
     * covers a local grab pushed through a portal after drag began, which a capture-only scheme
     * cannot see.
     */
    public static Vec3 mapGoal(ServerPlayer player, SubLevel sub, Vec3 goal) {
        Frame frame = FRAMES.get(player.getUUID());
        if (frame == null || !frame.subId.equals(sub.getUniqueId())) return goal;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
        if (parent == null) return goal;

        // Before parent flip, source-frame goals are raw. If the player already crossed the
        // active straddle portal while pulling, bring its destination-frame cursor back through
        // the live image. This is inverse of a normal seamless entity teleport, no velocity or
        // body pose is touched.
        if (!frame.completedTransit) {
            if (player.level() == parent) return goal;
            Portal exiting = IplStraddleCloneBody.getSessionPortalFrom(sub, parent);
            if (exiting == null || !exiting.getDestDim().equals(player.level().dimension())) return goal;
            return exiting.inverseTransformPoint(goal);
        }

        Portal portal = frame.resolve(player, parent);
        if (portal == null) return goal;

        // Different dimensions identify player frame by dimension key. Same-dimension portals
        // have two charts inside one Level, so source-plane side is meaningless once source and
        // exit apertures are separated or rotated. Select chart by the actual native body: map
        // player eye through portal and choose that chart only when it is nearer to the real body.
        if (player.level().dimension().equals(frame.sourceDim)) {
            if (frame.sourceDim.equals(frame.targetDim)
                && !playerUsesSourceFrame(frame, portal, player.getEyePosition(), sub)) {
                return goal;
            }
            return portal.transformPoint(goal);
        }
        return goal;
    }

    private static boolean playerUsesSourceFrame(
        Frame frame, Portal portal, Vec3 playerEye, SubLevel sub
    ) {
        dev.ryanhcode.sable.companion.math.BoundingBox3dc body = sub.boundingBox();
        double nativeDistance = distanceSqToBox(body, playerEye);
        double mappedDistance = distanceSqToBox(body, portal.transformPoint(playerEye));
        double difference = mappedDistance - nativeDistance;
        if (Math.abs(difference) > PORTAL_SIDE_EPSILON * PORTAL_SIDE_EPSILON) {
            // Mapping source-chart eye through the portal places it near body only while
            // player is still at source aperture. Once player follows the body to the exit,
            // raw eye is nearer and must remain untransformed.
            frame.playerUsesSourceFrame = mappedDistance < nativeDistance;
        }
        // Exact overlap at an aperture keeps last chart. A new post-transit frame defaults to
        // source because player has not crossed the exit aperture yet.
        return frame.playerUsesSourceFrame == null || frame.playerUsesSourceFrame;
    }

    private static double distanceSqToBox(
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box, Vec3 point
    ) {
        double x = Math.max(box.minX(), Math.min(box.maxX(), point.x));
        double y = Math.max(box.minY(), Math.min(box.maxY(), point.y));
        double z = Math.max(box.minZ(), Math.min(box.maxZ(), point.z));
        double dx = point.x - x, dy = point.y - y, dz = point.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Reframe server target immediately; do not wait for client handoff packet latency. */
    private static void reframeLiveSession(Portal portal, UUID playerId) {
        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(portal.getServer());
        if (hosting == null) return;
        PhysicsStaffServerHandler handler = PhysicsStaffServerHandler.get(hosting);
        Object session = ((IplStaffServerHandlerAccessorMixin) (Object) handler)
            .ipl$getDraggingSessions().get(playerId);
        if (!(session instanceof IplStaffDragSessionControl control)) return;
        qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
        control.ipl$reframeAfterTransit(new org.joml.Quaterniond(
            rotation.x, rotation.y, rotation.z, rotation.w));
    }

    private static final class Frame {
        final UUID subId;
        final UUID playerId;
        UUID portalId;
        ResourceKey<Level> sourceDim;
        ResourceKey<Level> targetDim;
        boolean completedTransit;
        Boolean playerUsesSourceFrame;

        Frame(UUID subId, UUID playerId, ResourceKey<Level> sourceDim) {
            this.subId = subId;
            this.playerId = playerId;
            this.sourceDim = sourceDim;
        }

        Portal resolve(ServerPlayer player, ServerLevel parent) {
            if (portalId == null || sourceDim == null || targetDim == null) return null;
            if (!targetDim.equals(parent.dimension())) {
                return null;
            }
            ServerLevel level = player.getServer().getLevel(sourceDim);
            if (level == null || !(McHelper.getEntityByUUID(level, portalId) instanceof Portal portal)) {
                return null;
            }
            return portal.getDestDim().equals(parent.dimension()) ? portal : null;
        }
    }

}
