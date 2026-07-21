package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative straddle parity sync (the replacement for the client's
 * heuristic plane-orientation guessing).
 *
 * <p>Which half of a straddling ship is "still here" is historical state — a ship 60%
 * through a portal is geometrically indistinguishable from one 40% through the other
 * way — and the only holder of that history is the transit controller's straddle latch.
 * This class mirrors the latch to clients as a per-ship snapshot of active
 * {@code (ship, portal)} sessions. The client rebuilds the clip plane each frame from
 * the synced portal entity's CURRENT transform (so moving portals stay smooth) and
 * takes only the parity — which portal face, hence which half — from here.
 *
 * <p>No sign bit is needed on the wire: the transit controller keys sessions on the
 * canonical ENTRANCE face, whose normal by IP convention points at the remaining
 * (source) half, and locks the crossing direction to exactly {@code -normal}. Portal
 * identity therefore carries the parity, and it rotates with the portal for free.
 *
 * <p>Snapshots are full per-ship state (not diffs): idempotent, order-tolerant, and a
 * late-joining client just gets one on track start. Payload is a few dozen bytes and
 * changes a handful of times per crossing, so it is broadcast to all players rather
 * than tracking-scoped — a client that doesn't know the ship ignores it.
 */
public final class IplStraddleSessionSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-sync");

    /** Ship → active straddle-session portal ids, in session-start order. */
    private static final Map<UUID, LinkedHashSet<UUID>> ACTIVE = new HashMap<>();

    private IplStraddleSessionSync() {}

    /** The transit controller latched a new (ship, portal) straddle session. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, Portal portal) {
        UUID shipId = ship.getUniqueId();
        LinkedHashSet<UUID> portals = ACTIVE.computeIfAbsent(shipId, k -> new LinkedHashSet<>());
        if (!portals.add(portal.getUUID())) return;

        LOG.info("[IPL-STRADDLE-SYNC] start ship={} portal={}", shipId, portal.getUUID());
        broadcast(server, shipId);
    }

    /** A latched session ended (backed out, left aperture, crossed, or reaped). */
    public static void onSessionEnd(MinecraftServer server, StraddleKey key, String reason) {
        LinkedHashSet<UUID> portals = ACTIVE.get(key.subLevelUuid());
        if (portals == null || !portals.remove(key.portalUuid())) return;
        if (portals.isEmpty()) ACTIVE.remove(key.subLevelUuid());

        LOG.info("[IPL-STRADDLE-SYNC] end ship={} portal={} ({})",
            key.subLevelUuid(), key.portalUuid(), reason);
        broadcast(server, key.subLevelUuid());
    }

    /** Track-start bootstrap: give one viewer the ship's current session snapshot. */
    public static void sendSnapshotTo(ServerPlayer viewer, UUID shipId) {
        send(viewer, shipId, encode(shipId));
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    private static void broadcast(MinecraftServer server, UUID shipId) {
        if (server == null) return;
        String encoded = encode(shipId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, shipId, encoded);
        }
    }

    private static void send(ServerPlayer player, UUID shipId, String encodedPortals) {
        qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
            player,
            "ipl.sable.client.IplStraddleSessionStore.RemoteCallables.snapshot",
            shipId.toString(), encodedPortals
        );
    }

    private static String encode(UUID shipId) {
        LinkedHashSet<UUID> portals = ACTIVE.get(shipId);
        if (portals == null || portals.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (UUID portalId : portals) {
            if (sb.length() > 0) sb.append(';');
            sb.append(portalId);
        }
        return sb.toString();
    }
}
