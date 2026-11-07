package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative piece state for clients (README, sections 2 and 7).
 *
 * <p>Which material of a straddling body is where is history, not geometry, so clients never
 * guess it. Two RPCs carry it, both tagged with the body's coordinate-frame generation:
 *
 * <ul>
 *   <li>{@code snapshot}: the body's live sessions ({@code chain path -> portal geometry,
 *       piece id}), resent whenever the set changes. The portal is carried IN the snapshot
 *       because IP only syncs portal entities relevant to each client's camera; the client
 *       builds a detached surrogate from it and upgrades to the live entity when present.</li>
 *   <li>{@code labels}: the piece labels of the sections that changed (or all of them after a
 *       flip and on track start), see {@link IplPieceMap#encode}.</li>
 * </ul>
 *
 * <p>The client rebuilds the aperture edges every frame from the portals' current transforms
 * and its own render pose, so moving portals stay smooth and nothing has to be sent per frame.
 */
public final class IplStraddleSessionSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-sync");
    /** Label sections per RPC (about 260 KiB). */
    private static final int LABEL_SECTIONS_PER_PACKET = 64;

    /** Ship -> advertised sessions in start order: chain path -> (portal geometry, piece id). */
    private static final Map<UUID, LinkedHashMap<String, Advertised>> ACTIVE = new HashMap<>();

    private record Advertised(String geometry, int node) {}

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

    /** Session start (or a moved portal): re-broadcasts only when the advertised entry changed. */
    public static void onSessionStart(MinecraftServer server, ServerSubLevel ship, StraddleKey key, Portal portal) {
        UUID shipId = ship.getUniqueId();
        LinkedHashMap<String, Advertised> sessions = ACTIVE.computeIfAbsent(shipId, k -> new LinkedHashMap<>());
        Advertised entry = new Advertised(encodePortal(portal), IplAtlasStraddleSession.sessionNode(key));
        if (entry.equals(sessions.put(key.path(), entry))) return;
        LOG.debug("[IPL-STRADDLE-SYNC] start ship={} path={} node={}", shipId, key.path(), entry.node());
        broadcast(server, shipId);
    }

    /** Keys {@code Portal.readAdditionalSaveData}/{@code Entity.load} need for geometry. */
    private static final Set<String> GEOMETRY_KEYS = Set.of(
        "Pos", "width", "height", "thickness",
        "axisWX", "axisWY", "axisWZ", "axisHX", "axisHY", "axisHZ",
        "dimensionTo", "destinationX", "destinationY", "destinationZ",
        "rotationA", "rotationB", "rotationC", "rotationD",
        "scale", "teleportable", "interactable", "isVisible");

    /**
     * Portal geometry for session snapshots and client-side handoff tails: only the keys the
     * rectangle and the isometry need (about 0.4 KB instead of the full entity NBT).
     */
    public static String encodePortal(Portal portal) {
        CompoundTag full = new CompoundTag();
        portal.saveWithoutId(full);
        CompoundTag tag = new CompoundTag();
        for (String key : GEOMETRY_KEYS) {
            Tag value = full.get(key);
            if (value != null) tag.put(key, value.copy());
        }
        tag.putString("ipl_source_dimension", portal.level().dimension().location().toString());
        tag.putString("entity_type", EntityType.getKey(portal.getType()).toString());
        return Base64.getEncoder().encodeToString(tag.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A session ended (emptied, frame invalidated, stale). */
    public static void onSessionEnd(MinecraftServer server, StraddleKey key, String reason) {
        LinkedHashMap<String, Advertised> sessions = ACTIVE.get(key.subLevelUuid());
        if (sessions == null || sessions.remove(key.path()) == null) return;
        if (sessions.isEmpty()) ACTIVE.remove(key.subLevelUuid());

        LOG.debug("[IPL-STRADDLE-SYNC] end ship={} path={} ({})", key.subLevelUuid(), key.path(), reason);
        broadcast(server, key.subLevelUuid());
    }

    /**
     * Forgets the advertised sessions of a ship that enters a new coordinate frame, without a
     * snapshot: clients drop the old frame's sessions themselves when they enter the new one
     * ({@code IplStraddleSessionStore.enterFrame}), and an empty old-frame snapshot would clear
     * the projection while their delayed render pose still shows the body crossing.
     */
    public static void forgetAllFor(UUID shipId) {
        ACTIVE.remove(shipId);
    }

    /** Drops every advertised session of one ship that is gone. */
    public static void endAllFor(MinecraftServer server, UUID shipId, String reason) {
        LinkedHashMap<String, Advertised> sessions = ACTIVE.remove(shipId);
        if (sessions == null || sessions.isEmpty()) return;
        LOG.debug("[IPL-STRADDLE-SYNC] end-all ship={} ({})", shipId, reason);
        broadcast(server, shipId);
    }

    /** Track-start bootstrap: give one viewer the ship's current sessions and labels. */
    public static void sendSnapshotTo(ServerPlayer viewer, UUID shipId) {
        send(viewer, shipId, encode(shipId));
        CompoundTag labels = IplPieceTracker.fullLabels(shipId);
        if (labels != null) sendLabels(viewer, shipId, labelPackets(labels));
    }

    /** Label changes of one body (see {@link IplPieceMap#encode}) to every player. */
    public static void sendLabels(MinecraftServer server, UUID shipId, CompoundTag labels) {
        if (server == null) return;
        List<CompoundTag> packets = labelPackets(labels);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendLabels(player, shipId, packets);
    }

    private static void sendLabels(ServerPlayer player, UUID shipId, List<CompoundTag> packets) {
        String frame = Long.toString(frame(shipId));
        int stamp = ipl.sable.IplTimeline.stamp(player.server);
        for (CompoundTag packet : packets) {
            // Server tick of the pose these labels belong to: the client applies them when its
            // interpolation clock shows that pose (IplStraddleSessionStore, timed payloads).
            packet.putInt(TICK_KEY, stamp);
            McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplStraddleSessionStore.RemoteCallables.labels",
                shipId.toString(), packet, frame
            );
        }
    }

    /**
     * A label payload as packets of at most {@link #LABEL_SECTIONS_PER_PACKET} sections, applied
     * in order: the client reads RPC NBT with a 2 MiB cap and a section costs 4 KiB, so a large
     * body's full payload would not arrive in one piece. Only the first packet clears.
     */
    private static List<CompoundTag> labelPackets(CompoundTag labels) {
        ListTag sections = labels.getList("s", Tag.TAG_COMPOUND);
        if (sections.size() <= LABEL_SECTIONS_PER_PACKET) return List.of(labels);
        List<CompoundTag> packets = new ArrayList<>();
        for (int from = 0; from < sections.size(); from += LABEL_SECTIONS_PER_PACKET) {
            ListTag slice = new ListTag();
            for (int i = from; i < Math.min(sections.size(), from + LABEL_SECTIONS_PER_PACKET); i++) {
                slice.add(sections.get(i));
            }
            CompoundTag packet = new CompoundTag();
            if (from == 0 && labels.getBoolean("full")) packet.putBoolean("full", true);
            packet.put("s", slice);
            if (labels.contains("e")) packet.put("e", labels.get("e").copy());
            packets.add(packet);
        }
        return packets;
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

    /** NBT key of the server tick (IplTimeline stamp) a session or label payload belongs to. */
    public static final String TICK_KEY = "t";

    private static void send(ServerPlayer player, UUID shipId, CompoundTag sessions) {
        sessions.putInt(TICK_KEY, ipl.sable.IplTimeline.stamp(player.server));
        McRemoteProcedureCall.tellClientToInvoke(
            player,
            "ipl.sable.client.IplStraddleSessionStore.RemoteCallables.snapshot",
            shipId.toString(), sessions, Long.toString(frame(shipId))
        );
    }

    /** {@code {s:[{p:path, g:geometry, n:piece}...]}}; NBT has no 32767-char string-argument limit. */
    private static CompoundTag encode(UUID shipId) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        LinkedHashMap<String, Advertised> sessions = ACTIVE.get(shipId);
        if (sessions != null) {
            for (Map.Entry<String, Advertised> entry : sessions.entrySet()) {
                CompoundTag e = new CompoundTag();
                e.putString("p", entry.getKey());
                e.putString("g", entry.getValue().geometry());
                e.putInt("n", entry.getValue().node());
                list.add(e);
            }
        }
        root.put("s", list);
        return root;
    }
}
