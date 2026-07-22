package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/**
 * Continuous coordinate frame of an active staff grab. Simulated sends a point relative to the
 * player each update, but the Rapier body lives in its sub-level parent frame. When either side
 * crosses a portal, this converts only the desired anchor point, never the body or its velocity.
 */
public final class IplStaffPortalDragState {

    private static final Map<UUID, Frame> FRAMES = new HashMap<>();

    private IplStaffPortalDragState() {}

    public static void begin(ServerPlayer player, UUID subId, org.joml.Vector3dc localAnchor) {
        Frame existing = FRAMES.get(player.getUUID());
        if (existing == null || !existing.subId.equals(subId)) {
            FRAMES.put(player.getUUID(), new Frame(subId));
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
     * Arm the new body frame only after server transit completed. In a same-dimension portal
     * the dimension key does not change, so this is the sole reliable boundary between an
     * aperture straddle (raw goal) and a fully mapped body (transformed goal).
     */
    public static void onTransitCompleted(UUID subId, Portal portal) {
        for (Frame frame : FRAMES.values()) {
            if (!frame.subId.equals(subId)) continue;
            frame.portals.clear();
            frame.portals.add(new PortalRef(portal.getUUID(), portal.level().dimension()));
            frame.sourceDim = portal.level().dimension();
            frame.targetDim = portal.getDestDim();
            frame.completedTransit = true;
            frame.sourceSide = null;
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

        // Client-side portal picks already encode their destination target. This server path is
        // only for a body grabbed in its native frame which later crossed a real portal.
        if (!frame.completedTransit) return goal;

        List<Portal> portals = frame.resolve(player, parent);
        if (portals == null) return goal;

        if (frame.sourceDim.equals(frame.targetDim)) {
            // Once the real body crossed a same-dimension portal, continue mapping while the
            // player remains on portal source side; switch to raw goal only after player crosses.
            if (frame.sourceSide == null) {
                frame.sourceSide = isPositiveSide(portals.get(0), player.getEyePosition());
            }
            return isPositiveSide(portals.get(0), player.getEyePosition()) == frame.sourceSide
                ? transformThrough(portals, goal) : goal;
        }

        // Different dimensions: while player remains in source world, convert its raw cursor
        // into the body parent frame once. When player crosses, both are native again.
        if (player.level().dimension().equals(frame.sourceDim)) {
            return transformThrough(portals, goal);
        }
        return goal;
    }

    private static Vec3 transformThrough(List<Portal> portals, Vec3 point) {
        for (Portal portal : portals) {
            point = portal.transformPoint(point);
        }
        return point;
    }

    private static boolean isPositiveSide(Portal portal, Vec3 point) {
        return portal.getNormal().dot(point.subtract(portal.getOriginPos())) > 0.0;
    }

    private static final class Frame {
        final UUID subId;
        final List<PortalRef> portals = new ArrayList<>();
        ResourceKey<Level> sourceDim;
        ResourceKey<Level> targetDim;
        boolean completedTransit;
        Boolean sourceSide;

        Frame(UUID subId) {
            this.subId = subId;
        }

        List<Portal> resolve(ServerPlayer player, ServerLevel parent) {
            if (portals.isEmpty() || sourceDim == null || targetDim == null) return null;
            if (!targetDim.equals(parent.dimension())) {
                return null;
            }
            List<Portal> resolved = new ArrayList<>(portals.size());
            for (PortalRef ref : portals) {
                ServerLevel level = player.getServer().getLevel(ref.sourceDim);
                if (level == null || !(McHelper.getEntityByUUID(level, ref.id) instanceof Portal portal)) {
                    return null;
                }
                resolved.add(portal);
            }
            return resolved.get(resolved.size() - 1).getDestDim().equals(parent.dimension()) ? resolved : null;
        }
    }

    private record PortalRef(UUID id, ResourceKey<Level> sourceDim) {}

}
