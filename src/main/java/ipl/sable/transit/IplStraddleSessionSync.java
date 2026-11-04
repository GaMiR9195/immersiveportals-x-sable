package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Map<UUID, LinkedHashMap<String, String>> ACTIVE = new HashMap<>();

    /** A same-dimension loop changes chart too: a dimension id is not a frame id. */
    private static final Map<UUID, Long> FRAMES = new HashMap<>();

    public static long frame(UUID ship) { return FRAMES.getOrDefault(ship, 0L); }
    public record FrameHop(long frame, IplGrabLink link, int serverTick) {}
    private static final Map<UUID, ArrayDeque<FrameHop>> HISTORY = new HashMap<>();

    public static void advanceFrame(UUID ship, Portal portal) {
        long next = frame(ship) + 1L;
        FRAMES.put(ship, next);
        int tick = portal.getServer().getTickCount();
        var history = HISTORY.computeIfAbsent(ship, ignored -> new ArrayDeque<>());
        history.addLast(new FrameHop(next, IplGrabLink.forward(portal), tick));
        // Sable permits up to 100 ticks of render delay; retain that plus network slack.
        while (!history.isEmpty() && tick - history.peekFirst().serverTick() > 160)
            history.removeFirst();
    }

    /** Exact body transits missed by a pick made on an older delayed visual frame. */
    public static List<FrameHop> hopsAfter(UUID ship, long sourceFrame) {
        if (sourceFrame == frame(ship)) return List.of();
        if (sourceFrame < 0 || sourceFrame > frame(ship)) return null;
        var history = HISTORY.get(ship);
        if (history == null) return null;
        var out = new ArrayList<FrameHop>();
        long expected = sourceFrame + 1;
        for (FrameHop hop : history) {
            if (hop.frame() <= sourceFrame) continue;
            if (hop.frame() != expected++) return null;
            out.add(hop);
        }
        return expected == frame(ship) + 1 ? List.copyOf(out) : null;
    }

    private IplStraddleSessionSync() {}

    /** The transit controller latched a new root (ship, portal) straddle session. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, Portal portal) {
        onSessionStart(server, ship, new StraddleKey(ship.getUniqueId(), portal.getUUID()), portal);
    }

    /** Root or chained session start/refresh; re-broadcasts only when the payload changed. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, StraddleKey key, Portal portal) {
        UUID shipId = ship.getUniqueId();
        LinkedHashMap<String, String> portals =
            ACTIVE.computeIfAbsent(shipId, k -> new LinkedHashMap<>());
        String encoded = encodePortal(portal, IplAtlasStraddleSession.isCompleted(key),
            IplAtlasStraddleSession.sessionAnchor(key));
        if (encoded.equals(portals.put(key.path(), encoded))) return;

        LOG.debug("[IPL-STRADDLE-SYNC] start ship={} path={}", shipId, key.path());
        broadcast(server, shipId);
    }

    /**
     * Portal geometry for session snapshots and client-side handoff render tails.
     *
     * <p>Только белый список ключей геометрии (≈0.4 КБ вместо 2–3 КБ полного
     * {@code saveWithoutId} с анимацией, формой и аттачментами): клиенту нужны прямоугольник
     * и изометрия. Полный снапшот сессий тела с цепочкой порталов превышал лимит строки RPC
     * ({@code writeUtf}, 32767 символов) и ронял кодирование пакета; сам снапшот идёт
     * NBT-списком.
     */
    public static String encodePortal(Portal portal) {
        return encodePortal(portal, false, null);
    }

    /** Keys {@code Portal.readAdditionalSaveData}/{@code Entity.load} need for geometry. */
    private static final Set<String> GEOMETRY_KEYS = Set.of(
        "Pos", "width", "height", "thickness",
        "axisWX", "axisWY", "axisWZ", "axisHX", "axisHY", "axisHZ",
        "dimensionTo", "destinationX", "destinationY", "destinationZ",
        "rotationA", "rotationB", "rotationC", "rotationD",
        "scale", "teleportable", "interactable", "isVisible");

    /** {@code anchor}: the session's plot-local passage anchor (constant for its lifetime). */
    private static String encodePortal(Portal portal, boolean completed, @Nullable Vec3 anchor) {
        CompoundTag full = new CompoundTag();
        portal.saveWithoutId(full);
        CompoundTag tag = new CompoundTag();
        for (String key : GEOMETRY_KEYS) {
            Tag value = full.get(key);
            if (value != null) tag.put(key, value.copy());
        }
        tag.putBoolean("ipl_session_completed", completed);
        if (anchor != null) {
            ListTag xyz = new ListTag();
            xyz.add(DoubleTag.valueOf(anchor.x));
            xyz.add(DoubleTag.valueOf(anchor.y));
            xyz.add(DoubleTag.valueOf(anchor.z));
            tag.put("ipl_session_anchor", xyz);
        }
        tag.putString("ipl_source_dimension", portal.level().dimension().location().toString());
        tag.putString("entity_type",
            EntityType.getKey(portal.getType()).toString());
        return Base64.getEncoder().encodeToString(
            tag.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A latched session ended (backed out, left aperture, crossed, or reaped). */
    public static void onSessionEnd(MinecraftServer server, StraddleKey key, String reason) {
        LinkedHashMap<String, String> portals = ACTIVE.get(key.subLevelUuid());
        if (portals == null || portals.remove(key.path()) == null) return;
        if (portals.isEmpty()) ACTIVE.remove(key.subLevelUuid());

        LOG.debug("[IPL-STRADDLE-SYNC] end ship={} path={} ({})", key.subLevelUuid(), key.path(), reason);
        broadcast(server, key.subLevelUuid());
    }

    /** Drops every advertised session of one ship (flip, move, removal). */
    public static void endAllFor(MinecraftServer server, UUID shipId, String reason) {
        LinkedHashMap<String, String> portals = ACTIVE.remove(shipId);
        if (portals == null || portals.isEmpty()) return;
        LOG.debug("[IPL-STRADDLE-SYNC] end-all ship={} ({})", shipId, reason);
        broadcast(server, shipId);
    }

    /** Declarative reap: advertised sessions of {@code shipId} not kept alive this tick. */
    public static void retainOnly(MinecraftServer server, UUID shipId, Set<StraddleKey> seen) {
        LinkedHashMap<String, String> portals = ACTIVE.get(shipId);
        if (portals == null) return;
        Set<String> keep = new HashSet<>();
        for (StraddleKey key : seen) {
            if (key.subLevelUuid().equals(shipId)) keep.add(key.path());
        }
        if (!portals.keySet().retainAll(keep)) return;
        if (portals.isEmpty()) ACTIVE.remove(shipId);
        broadcast(server, shipId);
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
        CompoundTag encoded = encode(shipId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, shipId, encoded);
        }
    }

    private static void send(ServerPlayer player, UUID shipId, CompoundTag sessions) {
        McRemoteProcedureCall.tellClientToInvoke(
            player,
            "ipl.sable.client.IplStraddleSessionStore.RemoteCallables.snapshot",
            shipId.toString(), sessions, Integer.toString(completedTrackingTick(player.server, shipId)),
            Long.toString(frame(shipId))
        );
    }

    /** The last completed tracking tick of the container that currently owns the ship. */
    private static int completedTrackingTick(MinecraftServer server, UUID shipId) {
        ServerSubLevelContainer fallback = null;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container =
                SubLevelContainer.getContainer(level);
            if (container == null) continue;
            if (fallback == null) fallback = container;
            if (container.getSubLevel(shipId) != null) {
                return container.trackingSystem().getInterpolationTick() - 1;
            }
        }
        return fallback == null ? Integer.MIN_VALUE : fallback.trackingSystem().getInterpolationTick() - 1;
    }

    /** {@code {s:[{p:path, g:geometry}...]}}; NBT has no 32767-char string-argument limit. */
    private static CompoundTag encode(UUID shipId) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        LinkedHashMap<String, String> portals = ACTIVE.get(shipId);
        if (portals != null) {
            for (Map.Entry<String, String> entry : portals.entrySet()) {
                CompoundTag e = new CompoundTag();
                e.putString("p", entry.getKey());
                e.putString("g", entry.getValue());
                list.add(e);
            }
        }
        root.put("s", list);
        return root;
    }
}
