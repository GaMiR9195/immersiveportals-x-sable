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
 * {@code (ship, chain path)} sessions. The client rebuilds the clip plane each frame from
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
     * Ship → active straddle sessions (chain path → serialized portal NBT, base64), in
     * session-start order. A root session's path is its portal UUID; a chained session's
     * path is {@code parentPath>portalUuid} (see {@link StraddleKey#path()}). The portal is
     * carried IN the snapshot because IP only syncs portal entities relevant to each
     * client's camera; the client builds a detached surrogate from the NBT and upgrades
     * to the live entity whenever it is present.
     */
    private static final Map<UUID, java.util.LinkedHashMap<String, String>> ACTIVE = new HashMap<>();

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

    /** The transit controller latched a new root (ship, portal) straddle session. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, Portal portal) {
        onSessionStart(server, ship, new StraddleKey(ship.getUniqueId(), portal.getUUID()), portal);
    }

    /** Root or chained session start/refresh; re-broadcasts only when the payload changed. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, StraddleKey key, Portal portal) {
        UUID shipId = ship.getUniqueId();
        java.util.LinkedHashMap<String, String> portals =
            ACTIVE.computeIfAbsent(shipId, k -> new java.util.LinkedHashMap<>());
        String encoded = encodePortal(portal, IplAtlasStraddleSession.isCompleted(key),
            IplAtlasStraddleSession.isWide(key));
        if (encoded.equals(portals.put(key.path(), encoded))) return;

        LOG.debug("[IPL-STRADDLE-SYNC] start ship={} path={}", shipId, key.path());
        broadcast(server, shipId);
    }

    /**
     * Portal geometry for session snapshots and client-side handoff render tails.
     *
     * <p>Русский комментарий: раньше сюда шёл ПОЛНЫЙ {@code saveWithoutId} портала
     * (анимация, форма, NeoForge-аттачменты, команды...) в base64 — 2-3 КБ на сессию.
     * Снапшот всех сессий тела склеивался в одну строку RPC, а IP пишет строки через
     * {@code writeUtf} с лимитом 32767 символов: цепочка порталов подряд давала десятки
     * сессий, сервер падал на кодировании пакета, клиент видел
     * {@code ClosedChannelException}. Клиенту нужен только прямоугольник и изометрия,
     * поэтому берём белый список ключей (≈0.4 КБ), а сам снапшот идёт как NBT-список.
     */
    public static String encodePortal(Portal portal) {
        return encodePortal(portal, false, false);
    }

    /** Keys {@code Portal.readAdditionalSaveData}/{@code Entity.load} need for geometry. */
    private static final java.util.Set<String> GEOMETRY_KEYS = java.util.Set.of(
        "Pos", "width", "height", "thickness",
        "axisWX", "axisWY", "axisWZ", "axisHX", "axisHY", "axisHZ",
        "dimensionTo", "destinationX", "destinationY", "destinationZ",
        "rotationA", "rotationB", "rotationC", "rotationD",
        "scale", "teleportable", "interactable", "isVisible");

    private static String encodePortal(Portal portal, boolean completed, boolean wide) {
        try {
            net.minecraft.nbt.CompoundTag full = new net.minecraft.nbt.CompoundTag();
            portal.saveWithoutId(full);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            for (String key : GEOMETRY_KEYS) {
                net.minecraft.nbt.Tag value = full.get(key);
                if (value != null) tag.put(key, value.copy());
            }
            tag.putBoolean("ipl_session_completed", completed);
            if (wide) tag.putBoolean("ipl_session_wide", true);
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
        java.util.LinkedHashMap<String, String> portals = ACTIVE.get(key.subLevelUuid());
        if (portals == null || portals.remove(key.path()) == null) return;
        if (portals.isEmpty()) ACTIVE.remove(key.subLevelUuid());

        LOG.debug("[IPL-STRADDLE-SYNC] end ship={} path={} ({})", key.subLevelUuid(), key.path(), reason);
        broadcast(server, key.subLevelUuid());
    }

    /** Drops every advertised session of one ship (flip, move, removal). */
    public static void endAllFor(MinecraftServer server, UUID shipId, String reason) {
        java.util.LinkedHashMap<String, String> portals = ACTIVE.remove(shipId);
        if (portals == null || portals.isEmpty()) return;
        LOG.debug("[IPL-STRADDLE-SYNC] end-all ship={} ({})", shipId, reason);
        broadcast(server, shipId);
    }

    /** Declarative reap: advertised sessions of {@code shipId} not kept alive this tick. */
    public static void retainOnly(MinecraftServer server, UUID shipId, java.util.Set<StraddleKey> seen) {
        java.util.LinkedHashMap<String, String> portals = ACTIVE.get(shipId);
        if (portals == null) return;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (StraddleKey key : seen) {
            if (key.subLevelUuid().equals(shipId)) keep.add(key.path());
        }
        if (!portals.keySet().retainAll(keep)) return;
        if (portals.isEmpty()) ACTIVE.remove(shipId);
        broadcast(server, shipId);
    }

    /** Every (ship, chain path) the sync currently advertises. */
    public static java.util.List<StraddleKey> activeKeys() {
        java.util.List<StraddleKey> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, java.util.LinkedHashMap<String, String>> entry : ACTIVE.entrySet()) {
            for (String path : entry.getValue().keySet()) {
                StraddleKey key = parsePath(entry.getKey(), path);
                if (key != null) out.add(key);
            }
        }
        return out;
    }

    private static StraddleKey parsePath(UUID shipId, String path) {
        try {
            int cut = path.lastIndexOf('>');
            if (cut < 0) return new StraddleKey(shipId, UUID.fromString(path));
            return new StraddleKey(shipId, UUID.fromString(path.substring(cut + 1)), path.substring(0, cut));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        net.minecraft.nbt.CompoundTag encoded = encode(shipId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, shipId, encoded);
        }
    }

    private static void send(ServerPlayer player, UUID shipId, net.minecraft.nbt.CompoundTag sessions) {
        try {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplStraddleSessionStore.RemoteCallables.snapshot",
                shipId.toString(), sessions, Integer.toString(completedTrackingTick(player.server, shipId)),
                Long.toString(frame(shipId))
            );
        } catch (Throwable t) {
            // Никогда не роняем соединение из-за диагностики/синхронизации образов.
            LOG.error("[IPL-STRADDLE-SYNC] snapshot send failed for ship {}", shipId, t);
        }
    }

    /** The last completed tracking tick of the container that currently owns the ship. */
    private static int completedTrackingTick(MinecraftServer server, UUID shipId) {
        dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer fallback = null;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null) continue;
            if (fallback == null) fallback = container;
            if (container.getSubLevel(shipId) != null) {
                return container.trackingSystem().getInterpolationTick() - 1;
            }
        }
        return fallback == null ? Integer.MIN_VALUE : fallback.trackingSystem().getInterpolationTick() - 1;
    }

    /** {@code {s:[{p:path, g:geometry}...]}}; NBT has no 32767-char string-argument limit. */
    private static net.minecraft.nbt.CompoundTag encode(UUID shipId) {
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        java.util.LinkedHashMap<String, String> portals = ACTIVE.get(shipId);
        if (portals != null) {
            for (Map.Entry<String, String> entry : portals.entrySet()) {
                net.minecraft.nbt.CompoundTag e = new net.minecraft.nbt.CompoundTag();
                e.putString("p", entry.getKey());
                e.putString("g", entry.getValue());
                list.add(e);
            }
        }
        root.put("s", list);
        return root;
    }
}
