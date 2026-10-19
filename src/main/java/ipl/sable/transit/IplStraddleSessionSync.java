package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
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
 * <p>Snapshots are full per-ship state, delivered on the ordered TCP stream. Each
 * carries a coordinate-frame generation and completed tracking tick. Late tracking
 * gets the current state after its frame stamp; untracked clients ignore broadcasts.
 * Geometry changes and finite completion update the payload as well as session starts.
 */
public final class IplStraddleSessionSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-sync");

    /**
     * Ship → active straddle-session portals (id → serialized portal NBT, base64), in
     * session-start order. The portal is carried IN the snapshot for the same reason
     * the handoff packet carries the portal transform: IP only syncs portal entities
     * relevant to each client's camera, and a cross-dimension reverse session names a
     * portal entity from the OTHER dimension — waiting for that entity to sync left a
     * multi-tick hole where the client could resolve nothing (invisible ship, riders
     * falling through). The client builds a detached surrogate from the NBT and
     * upgrades to the live entity whenever it is present.
     */
    private static final Map<UUID, java.util.LinkedHashMap<UUID, String>> ACTIVE = new HashMap<>();

    /** A same-dimension loop changes chart too: a dimension id is not a frame id. */
    private static final Map<UUID, Long> FRAMES = new HashMap<>();

    public static long frame(UUID ship) { return FRAMES.getOrDefault(ship, 0L); }
    public record FrameHop(long frame, IplGrabLink link, int serverTick) {}
    private static final Map<UUID, java.util.ArrayDeque<FrameHop>> HISTORY = new HashMap<>();

    public static void advanceFrame(UUID ship, Portal portal) {
        long next = frame(ship) + 1L;
        FRAMES.put(ship, next);
        int tick = portal.getServer().getTickCount();
        var history = HISTORY.computeIfAbsent(ship, ignored -> new java.util.ArrayDeque<>());
        history.addLast(new FrameHop(next, IplGrabLink.forward(portal), tick));
        // Sable permits up to 100 ticks of render delay; retain that plus network slack.
        while (!history.isEmpty() && tick - history.peekFirst().serverTick() > 160)
            history.removeFirst();
    }

    /** Exact body transits missed by a pick made on an older delayed visual frame. */
    public static java.util.List<FrameHop> hopsAfter(UUID ship, long sourceFrame) {
        if (sourceFrame == frame(ship)) return java.util.List.of();
        if (sourceFrame < 0 || sourceFrame > frame(ship)) return null;
        var history = HISTORY.get(ship);
        if (history == null) return null;
        var out = new java.util.ArrayList<FrameHop>();
        long expected = sourceFrame + 1;
        for (FrameHop hop : history) {
            if (hop.frame() <= sourceFrame) continue;
            if (hop.frame() != expected++) return null;
            out.add(hop);
        }
        return expected == frame(ship) + 1 ? java.util.List.copyOf(out) : null;
    }

    private IplStraddleSessionSync() {}

    /** The transit controller latched a new (ship, portal) straddle session. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, Portal portal) {
        UUID shipId = ship.getUniqueId();
        java.util.LinkedHashMap<UUID, String> portals =
            ACTIVE.computeIfAbsent(shipId, k -> new java.util.LinkedHashMap<>());
        String encoded = encodePortal(portal,
            IplAtlasStraddleSession.isCompleted(new StraddleKey(shipId, portal.getUUID())));
        if (encoded.equals(portals.put(portal.getUUID(), encoded))) return;

        LOG.debug("[IPL-STRADDLE-SYNC] start ship={} portal={}", shipId, portal.getUUID());
        broadcast(server, shipId);
    }

    /** Full portal geometry for session snapshots and client-side handoff render tails. */
    public static String encodePortal(Portal portal) {
        return encodePortal(portal, false);
    }

    private static String encodePortal(Portal portal, boolean completed) {
        try {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            portal.saveWithoutId(tag);
            tag.putBoolean("ipl_session_completed", completed);
            tag.putString("ipl_source_dimension", portal.level().dimension().location().toString());
            tag.putString("entity_type",
                net.minecraft.world.entity.EntityType.getKey(portal.getType()).toString());
            return java.util.Base64.getEncoder().encodeToString(
                tag.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOG.error("[IPL-STRADDLE-SYNC] portal encode failed for {}", portal.getUUID(), t);
            return "";
        }
    }

    /** A latched session ended (backed out, left aperture, crossed, or reaped). */
    public static void onSessionEnd(MinecraftServer server, StraddleKey key, String reason) {
        java.util.LinkedHashMap<UUID, String> portals = ACTIVE.get(key.subLevelUuid());
        if (portals == null || portals.remove(key.portalUuid()) == null) return;
        if (portals.isEmpty()) ACTIVE.remove(key.subLevelUuid());

        LOG.debug("[IPL-STRADDLE-SYNC] end ship={} portal={} ({})",
            key.subLevelUuid(), key.portalUuid(), reason);
        broadcast(server, key.subLevelUuid());
    }

    /**
     * Every (ship, portal) pair the sync currently advertises — swept by the
     * declarative reap alongside the physical session keys, so an advertised session
     * whose backing spawn never materialized cannot outlive its geometry.
     */
    public static java.util.List<StraddleKey> activeKeys() {
        java.util.List<StraddleKey> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, java.util.LinkedHashMap<UUID, String>> entry : ACTIVE.entrySet()) {
            for (UUID portalId : entry.getValue().keySet()) {
                out.add(new StraddleKey(entry.getKey(), portalId));
            }
        }
        return out;
    }

    /** Track-start bootstrap: give one viewer the ship's current session snapshot. */
    public static void sendSnapshotTo(ServerPlayer viewer, UUID shipId) {
        send(viewer, shipId, encode(shipId));
    }

    public static void clearAll() {
        ACTIVE.clear();
        FRAMES.clear();
        HISTORY.clear();
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
            shipId.toString(), encodedPortals, Integer.toString(completedTrackingTick(player.server)),
            Long.toString(frame(shipId))
        );
    }

    private static int completedTrackingTick(MinecraftServer server) {
        var hosting = server.getLevel(ipl.sable.dim.SableSubLevelDimension.SUBLEVELS);
        if (hosting == null) return Integer.MIN_VALUE;
        dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(hosting);
        return container == null ? Integer.MIN_VALUE
            : container.trackingSystem().getInterpolationTick() - 1;
    }

    private static String encode(UUID shipId) {
        java.util.LinkedHashMap<UUID, String> portals = ACTIVE.get(shipId);
        if (portals == null || portals.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, String> entry : portals.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }
}
