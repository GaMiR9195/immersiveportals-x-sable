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
 * Continuous coordinate frame of an active staff grab.
 *
 * <p>Simulated sends one player-relative cursor vector per tick; the PD constraint target
 * must be expressed in the grabbed body's NATIVE parent frame. The old implementation
 * selected that frame from a sticky player-eye heuristic, which flip-flopped mid-crossing
 * and yanked the body toward an unmapped cursor ("flying"). This version derives the frame
 * from the cursor GEOMETRY of the current tick:
 *
 * <ul>
 *   <li><b>Same-dimension portals</b>: the native body never transits while held (see the
 *       staff freeze in {@link SableTransitController}), so the only question is whether the
 *       player's cursor addresses the native half or the portal image. Both candidate goals
 *       are computed (raw, and inverse-mapped through the session portal) and the one nearer
 *       the native body wins, with a sticky margin so a mid-plane cursor can't oscillate.
 *       For a translation-only portal the raw goal already IS the correct native goal while
 *       pushing straight through — the chooser only flips once the player physically works
 *       from the exit side. Orientation is never touched: the body keeps its rotation.</li>
 *   <li><b>Cross-dimension, before parent flip</b>: a player who walked through the portal
 *       while pulling sends dest-frame cursors; they map back through the live session
 *       portal exactly once (unchanged behavior).</li>
 *   <li><b>Cross-dimension, after parent flip</b>: a player still on the source side sends
 *       source-frame cursors; they map forward through the recorded crossing portal
 *       (unchanged behavior). Only this path reframes the held orientation.</li>
 * </ul>
 */
public final class IplStaffPortalDragState {

    private static final Map<UUID, Frame> FRAMES = new HashMap<>();

    /**
     * Same-dimension chooser stickiness: the losing candidate must be nearer to the native
     * body by this many blocks before the frame flips. Keeps a cursor hovering near the
     * geometric midpoint (or a tiny-offset portal pair) from oscillating the constraint.
     */
    private static final double SAME_DIM_SWITCH_MARGIN = 1.5;

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
     * True while any staff drag session owns this body. The transit controller freezes
     * same-dimension rehome/transit for held bodies: a held construction just straddles on
     * its image colliders — fully in, fully back out — and only transits after release.
     */
    public static boolean isHeldByStaff(UUID subId) {
        for (Frame frame : FRAMES.values()) {
            if (frame.subId.equals(subId)) return true;
        }
        return false;
    }

    /**
     * Body parent changed. Cross-dimension only: raw staff goals still originate in the
     * player's current world, so record the crossing for forward mapping and rotate the held
     * orientation through the portal once. Same-dimension transits are frozen while held; if
     * one still lands here (release race), only the chooser state is reset — the orientation
     * must never be reframed, the body keeps its world rotation.
     */
    public static void onTransitCompleted(UUID subId, Portal portal) {
        boolean sameDim = portal.getDestDim().equals(portal.level().dimension());
        for (Frame frame : FRAMES.values()) {
            if (!frame.subId.equals(subId)) continue;
            frame.sameDimGoalMapped = null;
            if (sameDim) continue;
            frame.portalId = portal.getUUID();
            frame.sourceDim = portal.level().dimension();
            frame.targetDim = portal.getDestDim();
            frame.completedTransit = true;
            reframeLiveSession(portal, frame.playerId);
        }
    }

    /**
     * Convert the player-frame motor target into the grabbed body's current parent frame.
     * Pure per-tick geometry; the only retained state is the same-dimension stickiness flag.
     */
    public static Vec3 mapGoal(ServerPlayer player, SubLevel sub, Vec3 goal) {
        Frame frame = FRAMES.get(player.getUUID());
        if (frame == null || !frame.subId.equals(sub.getUniqueId())) return goal;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
        if (parent == null) return goal;

        // ---- Player and body parent in DIFFERENT dimensions (cross-dim only). ----
        if (!player.level().dimension().equals(parent.dimension())) {
            if (!frame.completedTransit) {
                // Before the parent flip the real body is still in its source frame. The
                // player crossed the active straddle portal while pulling: bring the
                // destination-frame cursor back through the live session portal.
                Portal exiting = IplStraddleCloneBody.getSessionPortalFrom(sub, parent);
                if (exiting != null && exiting.getDestDim().equals(player.level().dimension())) {
                    return exiting.inverseTransformPoint(goal);
                }
                return goal;
            }
            // After the parent flip a player left behind on the source side keeps sending
            // source-frame cursors; map them forward through the recorded crossing.
            Portal recorded = frame.resolve(player, parent);
            if (recorded != null && player.level().dimension().equals(frame.sourceDim)) {
                return recorded.transformPoint(goal);
            }
            return goal;
        }

        // ---- Player in the body's parent level. Same-dimension straddle chooser. ----
        Portal sameDim = sameDimSessionPortal(sub, parent);
        if (sameDim == null) {
            frame.sameDimGoalMapped = null;
            return goal;
        }

        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(sameDim);
        Vec3 mapped = mapping.unmapPoint(goal);

        dev.ryanhcode.sable.companion.math.BoundingBox3dc body = sub.boundingBox();
        double rawDistance = distanceToBox(body, goal);
        double mappedDistance = distanceToBox(body, mapped);

        // Sticky nearest-to-native-body selection. The native box stays put while held
        // (same-dim transit frozen), so this is stable geometry, not a moving reference:
        // cursor near the entrance region -> raw wins (goal continues straight through the
        // plane); cursor physically worked from the exit region -> mapped wins.
        boolean useMapped;
        if (frame.sameDimGoalMapped == null) {
            useMapped = mappedDistance + SAME_DIM_SWITCH_MARGIN < rawDistance;
        } else if (frame.sameDimGoalMapped) {
            useMapped = mappedDistance < rawDistance + SAME_DIM_SWITCH_MARGIN;
        } else {
            useMapped = mappedDistance + SAME_DIM_SWITCH_MARGIN < rawDistance;
        }
        frame.sameDimGoalMapped = useMapped;
        return useMapped ? mapped : goal;
    }

    /** The active straddle session whose entrance AND exit live in {@code parent}, or null. */
    private static Portal sameDimSessionPortal(SubLevel sub, ServerLevel parent) {
        Portal[] found = new Portal[1];
        IplStraddleCloneBody.forEachSessionFrom(sub, parent, (portal, mapping) -> {
            if (found[0] == null && portal.getDestDim().equals(parent.dimension())) {
                found[0] = portal;
            }
        });
        return found[0];
    }

    private static double distanceToBox(
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box, Vec3 point
    ) {
        double x = Math.max(box.minX(), Math.min(box.maxX(), point.x));
        double y = Math.max(box.minY(), Math.min(box.maxY(), point.y));
        double z = Math.max(box.minZ(), Math.min(box.maxZ(), point.z));
        double dx = point.x - x, dy = point.y - y, dz = point.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
        /** Same-dimension chooser stickiness; null until the first straddle decision. */
        Boolean sameDimGoalMapped;

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
