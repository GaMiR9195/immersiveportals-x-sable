package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import ipl.sable.mixin.client.StaffClientHandlerAccessor;
import ipl.sable.transit.IplGrabLink;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of {@link ipl.sable.transit.IplGrabChain}: per dragging player, the
 * portal chain from that player's eye to the grabbed body's parent frame.
 *
 * <p>Two sources, one truth:
 * <ul>
 *   <li><b>Server snapshots</b> (broadcast on every chain change) are authoritative and
 *       cover every player's beam, not just the local one.</li>
 *   <li><b>Local prediction</b> keeps the LOCAL player's chain correct within the same
 *       frame an event happens: the pick path is seeded at grab, the client teleport
 *       manager reports the exact portal the moment the camera crosses it, and the
 *       ordered rebase RPC appends body transits. Snapshots adopt over prediction only
 *       when their revision advances past the last one applied locally, so a stale
 *       broadcast can never rewind a fresher local chain.</li>
 * </ul>
 *
 * <p>No geometry inference anywhere: every mutation names its exact portal.
 */
public final class IplGrabChainClient {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-grab-chain-client");

    private record ChainState(UUID subId, long revision, List<IplGrabLink> chain, long bodyFrame, String seedId) {}

    /** Every dragging player's chain, from server snapshots. */
    private static final ConcurrentMap<UUID, ChainState> SNAPSHOTS = new ConcurrentHashMap<>();

    /** Local player's predicted chain (null when the local player is not dragging). */
    @Nullable
    private static volatile ChainState local;

    private IplGrabChainClient() {}

    // ------------------------------------------------------------------
    // Reads.
    // ------------------------------------------------------------------

    /** The chain for {@code ownerId}'s grab of {@code subId}, or empty when unknown. */
    public static List<IplGrabLink> chainFor(UUID ownerId, UUID subId) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(ownerId)) {
            ChainState mine = local;
            if (mine != null && mine.subId().equals(subId)) return mine.chain();
        }
        ChainState snapshot = SNAPSHOTS.get(ownerId);
        if (snapshot != null && snapshot.subId().equals(subId)) return snapshot.chain();
        return List.of();
    }

    /** True while the local player DRAGS {@code subId} and its predicted chain is live. */
    public static boolean hasLocalChainFor(UUID subId) {
        ChainState mine = local;
        if (mine == null || !mine.subId().equals(subId)) return false;
        PhysicsStaffClientHandler.ClientDragSession session =
            SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
        return session != null && session.dragSubLevel().getUniqueId().equals(subId);
    }

    public static long chainFrame(UUID ownerId, UUID subId) {
        LocalPlayer player = Minecraft.getInstance().player;
        ChainState state = player != null && player.getUUID().equals(ownerId) ? local : SNAPSHOTS.get(ownerId);
        return state != null && state.subId().equals(subId) ? state.bodyFrame() : IplParentDimSync.frame(subId);
    }

    // ------------------------------------------------------------------
    // Local events.
    // ------------------------------------------------------------------

    /**
     * Grab start: seed the local chain from the pick-time portal path and hand the same
     * path (identities only) to the server, which re-snapshots geometry itself.
     *
     * @param pickPath        portals the pick ray traversed FORWARD, in order
     * @param imagePath session path from the body to the clicked image, root first
     * @param sourceFrame body coordinate frame the pick SAW ({@link IplParentDimSync#pickFrame})
     */
    public static void seedLocal(UUID subId, List<Portal> pickPath, List<Portal> imagePath, long sourceFrame) {
        List<IplGrabLink> chain = List.of();
        ListTag steps = new ListTag();
        for (Portal portal : pickPath) {
            chain = IplGrabLink.append(chain, IplGrabLink.forward(portal));
            steps.add(StringTag.valueOf(portal.getUUID() + "@"
                + portal.level().dimension().location()));
        }
        // Undo the complete image path from its leaf back to the native body.
        for (int i = imagePath.size() - 1; i >= 0; i--) {
            Portal portal = imagePath.get(i);
            chain = IplGrabLink.append(chain, IplGrabLink.inverse(portal));
            steps.add(StringTag.valueOf(portal.getUUID() + "@"
                + portal.level().dimension().location() + "@inv"));
        }
        CompoundTag encodedPath = new CompoundTag();
        encodedPath.put("steps", steps);
        String seedId = UUID.randomUUID().toString();
        local = new ChainState(subId, 0L, chain, sourceFrame, seedId);
        McRemoteProcedureCallClient.tellServerToInvoke(
            "ipl.sable.transit.IplGrabChain.RemoteCallables.seedChain",
            subId.toString(), encodedPath, Long.toString(sourceFrame), seedId
        );
    }

    /**
     * Cross-dimension move: the local drag held the old client object, which is now a transit
     * ghost and will be removed. Simulated would read that removal as "grabbed body gone" and
     * release. The server already continues the same session on the successor (same UUID,
     * same chain, same seed), so the client continues on the successor too: plot-local anchor
     * shifted with the plot slot, the SAME orientation object (rebase RPCs keep rotating it),
     * the same hold distance.
     */
    public static void rebindDragToSuccessor(ClientSubLevel ghost, ClientSubLevel successor, Vector3d plotShift) {
        PhysicsStaffClientHandler handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        if (session == null || session.dragSubLevel() != ghost || successor == ghost) return;
        Vector3d anchor = new Vector3d(session.dragLocalAnchor()).add(plotShift);
        ((StaffClientHandlerAccessor) (Object) handler).ipl$setDragSession(
            new PhysicsStaffClientHandler.ClientDragSession(
                successor, anchor, session.dragOrientation(), session.distance()));
        LOG.debug("[IPL-GRAB-CHAIN-C] drag of {} continues on its successor in {}",
            successor.getUniqueId(), successor.getLevel().dimension().location());
    }

    /** Drag ended locally. The server clears its side from its own stop hook. */
    public static void clearLocal() {
        local = null;
    }

    public static void clearAll() {
        local = null;
        SNAPSHOTS.clear();
    }

    /**
     * The local player just crossed {@code portal} (called by the client teleportation
     * manager, same-dimension hops included). Zero-latency prediction; the matching
     * server snapshot lands a round-trip later and adopts an identical chain.
     */
    public static void onLocalPlayerTeleported(Portal portal) {
        ChainState mine = local;
        if (mine == null) return;

        PhysicsStaffClientHandler.ClientDragSession dragSession =
            SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
        if (dragSession != null
            && dragSession.dragSubLevel().getUniqueId().equals(mine.subId())) {
            // The server maps its stored player-relative goal by the same linear portal
            // transform. Keep subsequent stock drag packets in that frame too; otherwise
            // their unscaled distance would immediately reintroduce a target discontinuity.
            dragSession.setDistance(dragSession.distance() * portal.getScaling());
        }

        local = new ChainState(
            mine.subId(), mine.revision(),
            IplGrabLink.prepend(mine.chain(), IplGrabLink.inverse(portal)), mine.bodyFrame(), mine.seedId()
        );
        LOG.debug("[IPL-GRAB-CHAIN-C] local player crossed {} depth={}",
            portal.getUUID(), local.chain().size());
    }

    // ------------------------------------------------------------------
    // Server RPC entry points.
    // ------------------------------------------------------------------

    public static final class RemoteCallables {

        public static void rejectSeed(String subUuid, String seedId) {
            if (local == null || !local.seedId().equals(seedId)) return;
            var handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
            var session = handler.getDragSession();
            if (session != null && session.dragSubLevel().getUniqueId().toString().equals(subUuid)) {
                ((StaffClientHandlerAccessor) (Object) handler)
                    .ipl$stopDragging();
            }
        }

        /** Authoritative chain snapshot for one dragging player. */
        public static void snapshot(String playerUuid, String subUuid, String revision, CompoundTag encodedChain, String bodyFrame, String seedId) {
            UUID playerId = UUID.fromString(playerUuid);
            UUID subId = UUID.fromString(subUuid);
            long rev = Long.parseLong(revision);
            ChainState state = new ChainState(subId, rev, IplGrabLink.decodeChainTag(encodedChain), Long.parseLong(bodyFrame), seedId);
            SNAPSHOTS.put(playerId, state);

            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.getUUID().equals(playerId)) {
                ChainState mine = local;
                // Adopt over prediction only when the server has seen at least every
                // event we already applied locally (revision advances monotonically).
                if (mine != null && mine.seedId().equals(seedId) && rev >= mine.revision()) local = state;
            }
        }

        public static void clear(String playerUuid, String seedId) {
            UUID playerId = UUID.fromString(playerUuid);
            ChainState known = SNAPSHOTS.get(playerId);
            if (known != null && known.seedId().equals(seedId)) SNAPSHOTS.remove(playerId, known);
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.getUUID().equals(playerId)
                && local != null && local.seedId().equals(seedId)) {
                local = null;
            }
        }

        /**
         * The grabbed body transited a portal while the local player drags it. Ordered
         * with the server transit (independent of delayed visual handoff): append the link, rotate the local
         * drag orientation through the portal rotation (same-dimension included, or the held
         * rotation would be lost), and acknowledge so the server stops compensating this
         * client's in-flight packets.
         */
        public static void dragRebase(String subUuid, String revision, String encodedLink, String coordinateFrame, String seedId) {
            UUID subId = UUID.fromString(subUuid);
            IplGrabLink link = IplGrabLink.decode(encodedLink);
            ChainState mine = local;
            long rev = Long.parseLong(revision);
            // A release/re-grab of the SAME UUID is a different session. Old RPCs
            // and old acks must not rotate or clear this new session's state.
            if (mine == null || !mine.subId().equals(subId) || !mine.seedId().equals(seedId)) return;
            if (rev > mine.revision()) {
                local = new ChainState(subId, rev,
                    IplGrabLink.append(mine.chain(), link), Long.parseLong(coordinateFrame), seedId);
            }

            PhysicsStaffClientHandler handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
            PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
            if (rev > mine.revision() && session != null && session.dragSubLevel().getUniqueId().equals(subId)) {
                session.dragOrientation().set(
                    new Quaterniond(link.rotation()).mul(session.dragOrientation()));
            }

            McRemoteProcedureCallClient.tellServerToInvoke(
                "ipl.sable.transit.IplGrabChain.RemoteCallables.ackRebase", revision, seedId
            );
            LOG.debug("[IPL-GRAB-CHAIN-C] rebase sub={} rev={} depth={}",
                subId, rev, local == null ? -1 : local.chain().size());
        }
    }
}
