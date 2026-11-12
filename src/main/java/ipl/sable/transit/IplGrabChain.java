package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import ipl.sable.atlas.IplScenes;
import ipl.sable.duck.IplStaffDragSessionControl;
import ipl.sable.mixin.StaffServerHandlerAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative grab chain: THE coordinate frame of an active staff drag.
 *
 * <p>Frames are never inferred from geometry snapshots (distance-to-box goal choosers,
 * sticky switch margins, nearest-portal-after-dimension-change guessing); this class records
 * them from the EVENTS that change them:
 *
 * <ul>
 *   <li><b>Grab start</b> seeds the chain from the pick-time portal path (the client
 *       raycast walked exact portals; their UUIDs arrive by RPC and the server
 *       re-snapshots each portal's transform itself).</li>
 *   <li><b>Player teleport</b> (from {@code ServerTeleportationManager}, which knows the
 *       exact portal — same-dimension hops included) prepends the portal's INVERSE link
 *       and rotates the stored player-relative cursor vector, so the folded goal is
 *       continuous through the hop by construction.</li>
 *   <li><b>Body transit</b> (from {@code IplTransitController} after a successful
 *       parent flip) appends the crossed portal FORWARD and reframes the held
 *       orientation through the portal rotation — same-dimension transits included.</li>
 * </ul>
 *
 * <p>Adjacent links that compose to the identity annihilate, so walking or dragging back
 * through a portal pops the chain instead of growing it, and any sequential multi-portal
 * recursion (in, out, through another, all the way back) is just chain algebra.
 *
 * <p>The PD goal mapping is a pure fold of the absolute cursor point through the chain
 * ({@link #mapGoal}). Straddle sessions never affect the goal: while a body straddles,
 * its native pose is authoritative and the folded goal simply continues past the portal
 * plane; image colliders own the far side.
 *
 * <p><b>Orientation ack protocol.</b> A body transit is server-initiated, so drag packets
 * composed by the client BEFORE it learned of the transit can arrive AFTER the server
 * reframed the held orientation, stomping it with a stale value for a tick. Each transit
 * therefore records a pending rotation under a new revision; incoming packet orientations
 * are premultiplied by the unacknowledged pending rotations until the client acknowledges
 * the revision (it does so while processing the ordered rebase RPC). TCP ordering makes
 * this exact, not heuristic. Player teleports need no ack: they are client-initiated, so
 * every packet after the client's own teleport is already in the new frame.
 */
public final class IplGrabChain {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-grab-chain");

    private static final Map<UUID, Frame> FRAMES = new HashMap<>();

    private IplGrabChain() {}

    // ------------------------------------------------------------------
    // Lifecycle.
    // ------------------------------------------------------------------

    /** Ensure a frame exists for this drag (idempotent; the seed RPC usually created it). */
    public static void begin(ServerPlayer player, UUID subId) {
        Frame existing = FRAMES.get(player.getUUID());
        if (existing == null || !existing.subId.equals(subId)) {
            FRAMES.put(player.getUUID(), new Frame(subId, player.getUUID()));
            broadcast(player.server, player.getUUID());
        }
    }

    public static void end(MinecraftServer server, UUID playerId) {
        Frame removed = FRAMES.remove(playerId);
        if (removed != null) broadcastClear(server, playerId, removed.seedId);
    }

    public static void clearAll() {
        FRAMES.clear();
    }

    /** Players whose active staff session owns this body (rehome handoff + rebase targets). */
    public static Set<UUID> getDraggingPlayers(UUID subId) {
        Set<UUID> players = new HashSet<>();
        for (Map.Entry<UUID, Frame> entry : FRAMES.entrySet()) {
            if (entry.getValue().subId.equals(subId)) players.add(entry.getKey());
        }
        return players;
    }

    // ------------------------------------------------------------------
    // Frame-changing events.
    // ------------------------------------------------------------------

    /**
     * The player crossed a portal (any pair of dimensions, including the same one).
     * Called by {@code ServerTeleportationManager} with the exact portal crossed.
     */
    public static void onPlayerTeleported(
        ServerPlayer player, Portal portal, Vec3 previousEyePosition
    ) {
        Frame frame = FRAMES.get(player.getUUID());
        if (frame == null) return;

        frame.chain = IplGrabLink.prepend(frame.chain, IplGrabLink.inverse(portal));
        frame.revision++;

        // The stored cursor vector was composed from the pre-teleport look direction. IP
        // rotates the client camera through the portal, so the next packet's vector is the
        // portal rotation applied to the old one. Apply the same rotation to the stored
        // value so packets and the restored substep interpolation use one frame.
        IplStaffDragSessionControl session = liveSession(player.server, player.getUUID());
        if (session != null) {
            session.ipl$reframePlayerGoal(portal, previousEyePosition);
        }

        LOG.debug("[IPL-GRAB-CHAIN] player {} crossed portal {} ({} -> {}), depth={}",
            player.getUUID(), portal.getUUID(),
            portal.level().dimension().location(), portal.getDestDim().location(),
            frame.chain.size());
        broadcast(player.server, player.getUUID());
    }

    /**
     * The grabbed body completed a transit through {@code portal} (parent flip executed).
     * Applies to every player currently dragging it: append the forward link, reframe the
     * live constraint orientation, arm the pending-rotation window, and send the ordered
     * rebase RPC so the client rotates its own drag orientation and acknowledges.
     */
    public static void onBodyTransit(MinecraftServer server, UUID subId, Portal portal) {
        IplGrabLink link = IplGrabLink.forward(portal);
        Quaterniond rotation = rotationOf(portal);
        boolean identityRotation = isIdentity(rotation);

        for (Frame frame : FRAMES.values()) {
            if (!frame.subId.equals(subId)) continue;

            frame.chain = IplGrabLink.append(frame.chain, link);
            frame.revision++;

            if (!identityRotation) {
                frame.pendingRotations.add(new PendingRotation(frame.revision, new Quaterniond(rotation)));
            }
            // The body is now in portal destination frame while its native motor still
            // holds the prior source-frame target. Reframe it NOW, including identity-
            // rotation portals, rather than letting the next physics substep correct it.
            IplStaffDragSessionControl session = liveSession(server, frame.playerId);
            if (session != null) {
                session.ipl$reframeAfterTransit(portal);
            }

            ServerPlayer player = server.getPlayerList().getPlayer(frame.playerId);
            if (player != null) {
                McRemoteProcedureCall.tellClientToInvoke(
                    player,
                    "ipl.sable.client.IplGrabChainClient.RemoteCallables.dragRebase",
                    subId.toString(), Long.toString(frame.revision), link.encode(),
                    Long.toString(IplStraddleSessionSync.frame(subId)), frame.seedId
                );
            }
            LOG.debug("[IPL-GRAB-CHAIN] body {} transited portal {} for player {}, depth={} rev={}",
                subId, portal.getUUID(), frame.playerId, frame.chain.size(), frame.revision);
            broadcast(server, frame.playerId);
        }
    }

    /**
     * Cross-dimension move, BEFORE the source body is removed: drop the native staff joints
     * that hold it while the body (and its pipeline) are still valid. The sessions survive.
     */
    public static void beforeCrossMove(ServerSubLevel ship) {
        for (Object session : sessionsOf(PhysicsStaffServerHandler.get(ship.getLevel())).values()) {
            if (session instanceof IplStaffDragSessionControl control && control.ipl$body() == ship) {
                control.ipl$removeConstraint();
            }
        }
    }

    /**
     * Cross-dimension move completed: Sable now holds the body as {@code successor} (same UUID)
     * in the destination level. Every staff session of the old object continues on the
     * successor in the destination level's handler -- same player, cursor vector, orientation,
     * seed and grab chain. Without this the source handler dropped the session on its next tick
     * (old object removed) and ended the chain, the client's next drag packet opened a fresh
     * session with an EMPTY chain in the destination, and the cursor point of the player's world
     * was applied in the other world: the body flew away.
     *
     * <p>Runs before {@link #onBodyTransit}, which then appends the crossed portal and reframes
     * the (now live) session exactly like a same-dimension flip.
     */
    public static void afterCrossMove(ServerSubLevel from, ServerSubLevel successor, Vector3dc plotShift) {
        PhysicsStaffServerHandler source = PhysicsStaffServerHandler.get(from.getLevel());
        PhysicsStaffServerHandler destination = PhysicsStaffServerHandler.get(successor.getLevel());
        if (source == destination) return;
        Map<UUID, Object> sourceSessions = sessionsOf(source);
        Map<UUID, Object> destinationSessions = sessionsOf(destination);
        // Collect first: fastutil's map entries are index views and die with removal.
        List<UUID> players = new ArrayList<>(1);
        for (Map.Entry<UUID, Object> entry : sourceSessions.entrySet()) {
            if (entry.getValue() instanceof IplStaffDragSessionControl control && control.ipl$body() == from) {
                players.add(entry.getKey());
            }
        }
        for (UUID player : players) {
            Object session = sourceSessions.remove(player);
            if (!(session instanceof IplStaffDragSessionControl control)) continue;
            control.ipl$rebindBody(successor, plotShift);
            Object displaced = destinationSessions.put(player, session);
            if (displaced != session && displaced instanceof IplStaffDragSessionControl stale) {
                stale.ipl$removeConstraint();
            }
            LOG.debug("[IPL-GRAB-CHAIN] staff session of {} follows body {} into {}",
                player, successor.getUniqueId(), successor.getLevel().dimension().location());
        }
        if (!players.isEmpty()) {
            accessor(source).ipl$setDraggingSessionsDirty(true);
            accessor(destination).ipl$setDraggingSessionsDirty(true);
        }
    }

    private static StaffServerHandlerAccessor accessor(PhysicsStaffServerHandler handler) {
        return (StaffServerHandlerAccessor) (Object) handler;
    }

    private static Map<UUID, Object> sessionsOf(PhysicsStaffServerHandler handler) {
        return accessor(handler).ipl$getDraggingSessions();
    }

    // ------------------------------------------------------------------
    // Goal + packet mapping.
    // ------------------------------------------------------------------

    /**
     * Fold the absolute cursor goal (player world frame) through the chain into the
     * grabbed body's parent frame. Pure function of recorded events — no geometry
     * inference. On a desync (chain endpoints disagree with reality) there is NO goal:
     * the caller releases the grip instead of pulling the body to a point of another chart.
     * The desync is logged: visible, never silently guessed around.
     */
    @Nullable
    public static Vec3 mapGoal(ServerPlayer player, ServerSubLevel sub, Vec3 goal) {
        Frame frame = FRAMES.get(player.getUUID());
        ServerLevel parent = sub.getLevel();
        if (frame == null || !frame.subId.equals(sub.getUniqueId())) {
            return player.level().dimension().equals(parent.dimension()) ? goal : null;
        }

        if (frame.chain.isEmpty()) {
            if (!player.level().dimension().equals(parent.dimension())) {
                frame.logDesync(LOG, "empty chain but player {} is in {} while body parent is {}",
                    player.getUUID(), player.level().dimension().location(),
                    parent.dimension().location());
                return null;
            }
            return goal;
        }

        IplGrabLink first = frame.chain.get(0);
        IplGrabLink last = frame.chain.get(frame.chain.size() - 1);
        if (!first.fromDim().equals(player.level().dimension())
            || !last.toDim().equals(parent.dimension())) {
            frame.logDesync(LOG, "chain endpoints {}->{} disagree with player {} / parent {}",
                first.fromDim().location(), last.toDim().location(),
                player.level().dimension().location(), parent.dimension().location());
            return null;
        }

        return IplGrabLink.fold(frame.chain, goal);
    }

    /**
     * Premultiply an incoming packet orientation by the rotations of transits the client
     * has not acknowledged yet (see the class doc). Identity when nothing is pending.
     */
    public static Quaterniond adjustIncomingOrientation(UUID playerId, Quaterniond incoming) {
        Frame frame = FRAMES.get(playerId);
        if (frame == null || frame.pendingRotations.isEmpty()) return incoming;
        Quaterniond adjusted = new Quaterniond(incoming);
        for (PendingRotation pending : frame.pendingRotations) {
            adjusted.premul(pending.rotation());
        }
        return adjusted;
    }

    // ------------------------------------------------------------------
    // Client RPC entry points.
    // ------------------------------------------------------------------

    public static final class RemoteCallables {

        /**
         * Grab start: the client's pick raycast walked these portals, in order, from the
         * player's world to the hit. Only identities are trusted — the server resolves
         * each portal itself and snapshots server-side geometry. An unresolvable or
         * discontinuous path rejects the grab, never an identity-frame guess.
         */
        public static void seedChain(ServerPlayer player, String subUuid, CompoundTag encodedPath, String sourceFrame, String seedId) {
            UUID subId = UUID.fromString(subUuid);
            Frame frame = new Frame(subId, player.getUUID());
            frame.seedId = UUID.fromString(seedId).toString();
            List<IplGrabLink> resolved;
            try {
                resolved = resolveSeed(player, encodedPath);
            } catch (IllegalArgumentException ex) {
                LOG.warn("[IPL-GRAB-CHAIN] malformed seed for {}", player.getUUID());
                resolved = null;
            }
            var missed = IplStraddleSessionSync.hopsAfter(subId, Long.parseLong(sourceFrame));
            if (missed == null || resolved == null) {
                // Never guess a transform or bind an old visual pick to a new body.
                rejectSeed(player, subUuid, frame.seedId);
                LOG.warn("[IPL-GRAB-CHAIN] invalid or expired pick frame {} for {}", sourceFrame, subId);
                return;
            }
            List<IplGrabLink> chain = resolved;
            for (var hop : missed) chain = IplGrabLink.append(chain, hop.link());
            // The seeded chain must end in the world that owns the body NOW. A pick of an
            // instance the server no longer has there would otherwise bind a cursor of one
            // chart to a body of another.
            ServerLevel owner = IplScenes.ownerLevel(player.server, subId);
            ResourceKey<Level> end = chainEnd(player, chain);
            if (owner != null && !end.equals(owner.dimension())) {
                rejectSeed(player, subUuid, frame.seedId);
                LOG.warn("[IPL-GRAB-CHAIN] seed for {} ends in {} but the body is in {} (pick frame {})",
                    subId, end.location(), owner.dimension().location(), sourceFrame);
                return;
            }
            frame.chain = chain;
            for (var hop : missed) {
                frame.revision++;
                if (!isIdentity(hop.link().rotation())) frame.pendingRotations.add(
                    new PendingRotation(frame.revision, new Quaterniond(hop.link().rotation())));
                McRemoteProcedureCall.tellClientToInvoke(player,
                    "ipl.sable.client.IplGrabChainClient.RemoteCallables.dragRebase",
                    subUuid, Long.toString(frame.revision), hop.link().encode(), Long.toString(hop.frame()), frame.seedId);
            }
            FRAMES.put(player.getUUID(), frame);
            LOG.debug("[IPL-GRAB-CHAIN] seeded chain for player {} sub {} depth={}",
                player.getUUID(), subId, frame.chain.size());
            broadcast(player.server, player.getUUID());
        }

        /** Client processed the rebase RPC for this revision; stop compensating packets. */
        public static void ackRebase(ServerPlayer player, String revision, String seedId) {
            Frame frame = FRAMES.get(player.getUUID());
            if (frame == null || !frame.seedId.equals(seedId)) return;
            long acked = Long.parseLong(revision);
            frame.pendingRotations.removeIf(pending -> pending.revision() <= acked);
        }
    }

    /**
     * Resolve a seed path of {@code portalUuid@dimensionId} steps into links. A step with
     * an {@code @inv} suffix is an image grab: the click landed on that session portal's
     * IMAGE, so the link is the portal's inverse (image frame back to the body frame). The
     * dimension is always the portal ENTITY's own level; continuity is checked against the
     * side the step enters from.
     */
    @Nullable
    private static List<IplGrabLink> resolveSeed(ServerPlayer player, CompoundTag encodedPath) {
        if (encodedPath == null) return List.of();
        ListTag steps = encodedPath.getList("steps", Tag.TAG_STRING);
        List<IplGrabLink> chain = new ArrayList<>(4);
        ResourceKey<Level> at = player.level().dimension();
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.getString(i);
            boolean inverse = step.endsWith("@inv");
            String body = inverse ? step.substring(0, step.length() - "@inv".length()) : step;
            int sep = body.indexOf('@');
            if (sep < 0) return failSeed(player, encodedPath, "malformed step " + step);
            UUID portalId = UUID.fromString(body.substring(0, sep));
            ResourceKey<Level> dim = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(body.substring(sep + 1)));
            ServerLevel level = player.server.getLevel(dim);
            Portal portal = level == null ? null : findPortal(level, portalId);
            if (portal == null) return failSeed(player, encodedPath, "unresolvable portal " + portalId);
            IplGrabLink link = inverse ? IplGrabLink.inverse(portal) : IplGrabLink.forward(portal);
            if (!link.fromDim().equals(at)) {
                return failSeed(player, encodedPath, "discontinuous at " + step);
            }
            chain.add(link);
            at = link.toDim();
        }
        return List.copyOf(chain);
    }

    /** Stop every staff session of the player and tell its client the pick was refused. */
    private static void rejectSeed(ServerPlayer player, String subUuid, String seedId) {
        for (ServerLevel any : player.server.getAllLevels()) {
            PhysicsStaffServerHandler.get(any).stopDragging(player.getUUID());
        }
        McRemoteProcedureCall.tellClientToInvoke(player,
            "ipl.sable.client.IplGrabChainClient.RemoteCallables.rejectSeed", subUuid, seedId);
    }

    /** The world a chain leads into from the player's world. */
    private static ResourceKey<Level> chainEnd(ServerPlayer player, List<IplGrabLink> chain) {
        return chain.isEmpty() ? player.level().dimension() : chain.get(chain.size() - 1).toDim();
    }

    @Nullable
    private static List<IplGrabLink> failSeed(ServerPlayer player, CompoundTag path, String reason) {
        LOG.warn("[IPL-GRAB-CHAIN] seed rejected for {} ({}): {}", player.getUUID(), reason, path);
        return null;
    }

    @Nullable
    private static Portal findPortal(ServerLevel level, UUID portalId) {
        Entity entity = level.getEntity(portalId);
        if (entity instanceof Portal portal && !portal.isRemoved()) return portal;
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (portal.getUUID().equals(portalId) && !portal.isRemoved()) return portal;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    @Nullable
    private static IplStaffDragSessionControl liveSession(MinecraftServer server, UUID playerId) {
        // Drag sessions live in the handler of the level that owns the grabbed body.
        for (ServerLevel level : server.getAllLevels()) {
            Object session = sessionsOf(PhysicsStaffServerHandler.get(level)).get(playerId);
            if (session instanceof IplStaffDragSessionControl control) return control;
        }
        return null;
    }

    private static Quaterniond rotationOf(Portal portal) {
        DQuaternion q = portal.getRotationD();
        return q == null ? new Quaterniond() : new Quaterniond(q.x, q.y, q.z, q.w);
    }

    private static boolean isIdentity(Quaterniond q) {
        return Math.abs(q.x) < IplGrabLink.ROTATION_EPSILON
            && Math.abs(q.y) < IplGrabLink.ROTATION_EPSILON
            && Math.abs(q.z) < IplGrabLink.ROTATION_EPSILON
            && Math.abs(Math.abs(q.w) - 1.0) < IplGrabLink.ROTATION_EPSILON;
    }

    /** Broadcast this player's chain snapshot to every client (beams are visible to all). */
    private static void broadcast(MinecraftServer server, UUID playerId) {
        if (server == null) return;
        Frame frame = FRAMES.get(playerId);
        if (frame == null) return;
        CompoundTag encoded = IplGrabLink.encodeChainTag(frame.chain);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            McRemoteProcedureCall.tellClientToInvoke(
                viewer,
                "ipl.sable.client.IplGrabChainClient.RemoteCallables.snapshot",
                playerId.toString(), frame.subId.toString(),
                Long.toString(frame.revision), encoded, Long.toString(IplStraddleSessionSync.frame(frame.subId)), frame.seedId
            );
        }
    }

    private static void broadcastClear(MinecraftServer server, UUID playerId, String seedId) {
        if (server == null) return;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            McRemoteProcedureCall.tellClientToInvoke(
                viewer,
                "ipl.sable.client.IplGrabChainClient.RemoteCallables.clear",
                playerId.toString(), seedId
            );
        }
    }

    private record PendingRotation(long revision, Quaterniond rotation) {}

    private static final class Frame {
        final UUID subId;
        final UUID playerId;
        List<IplGrabLink> chain = List.of();
        long revision;
        String seedId = "";
        final List<PendingRotation> pendingRotations = new ArrayList<>(2);
        private long lastDesyncLogMs;

        Frame(UUID subId, UUID playerId) {
            this.subId = subId;
            this.playerId = playerId;
        }

        void logDesync(Logger log, String message, Object... args) {
            long now = System.currentTimeMillis();
            if (now - lastDesyncLogMs < 2000) return;
            lastDesyncLogMs = now;
            log.warn("[IPL-GRAB-CHAIN] desync: " + message, args);
        }
    }
}
