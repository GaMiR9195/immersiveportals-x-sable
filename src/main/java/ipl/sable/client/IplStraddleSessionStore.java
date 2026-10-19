package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of the server's straddle sessions (see
 * {@code IplStraddleSessionSync}): per ship, the portals of its active straddle
 * sessions, replaced wholesale by each snapshot RPC.
 *
 * <p>This store carries ONLY parity — which portal face the ship is crossing, hence
 * which half is "still here". Plane geometry is rebuilt every frame from the resolved
 * portal's current transform, so moving portals stay smooth while parity can never
 * flicker: it changes exactly when the server's latch does.
 *
 * <p>Portal resolution prefers the LIVE entity in the ship's parent level, but each
 * snapshot also carries the portal's full NBT (global-portal-style), from which a
 * detached surrogate is built when the entity isn't synced yet — a cross-dimension
 * reverse session names a portal from the other dimension, and IP only syncs portal
 * entities relevant to the camera; without the surrogate, the handoff had a multi-tick
 * hole with no projection and no collision image.
 */
public final class IplStraddleSessionStore {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-store");

    private record SessionPortal(UUID portalId, String portalNbtB64, boolean completed) {
        SessionPortal(UUID id, String payload) { this(id, payload, false); }
    }
    private record DeferredSnapshot(String payload, int endTick) {}
    /** Later chart's sessions cannot replace the delayed current chart's state. */
    private static final Map<UUID, java.util.NavigableMap<Long, DeferredSnapshot>> FUTURE = new java.util.HashMap<>();

    /** Ship → active session portals (session-start order; empty never stored). */
    private static final ConcurrentMap<UUID, List<SessionPortal>> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Sessions the server has retired but the delayed render pose has not finished leaving.
     * Atlas physics removes its image at the authoritative exit tick; rendering must keep the
     * complementary clipped instances until the client catches up or a parent handoff remaps
     * the pose atomically.
     */
    private static final ConcurrentMap<UUID, List<SessionPortal>> RETIRED = new ConcurrentHashMap<>();

    /** Detached surrogate portals built from snapshot NBT, by portal id. */
    private static final ConcurrentMap<UUID, Portal> SURROGATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, String> SURROGATE_PAYLOADS = new ConcurrentHashMap<>();
    /** The source tracking tick that ended a tail; never a wall-clock timeout. */
    private static final ConcurrentMap<UUID, Map<UUID, Integer>> RETIRED_END_TICKS = new ConcurrentHashMap<>();

    /** Server flips without a full client straddle keep this portal as a render-only tail. */
    private static final ConcurrentMap<UUID, SessionPortal> HANDOFF_VISUALS = new ConcurrentHashMap<>();

    private IplStraddleSessionStore() {}

    /**
     * The authoritative straddle portal for this ship, or null when the server has no
     * session for it. Live entity in the parent level when synced; snapshot surrogate
     * otherwise.
     */
    @Nullable
    public static Portal resolvePortal(ClientSubLevel sub) {
        if (sub == null) return null;
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return null;
        }
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return null;
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            if (live != null) return live;
            Portal surrogate = surrogate(sessionPortal, level);
            if (surrogate != null) return surrogate;
        }
        return null;
    }

    public static boolean hasSession(UUID shipId) {
        return SESSIONS.containsKey(shipId);
    }

    /**
     * Resolves portal geometry carried by a parent-handoff RPC. The server may finish a
     * crossing before IP tracks the source portal for this viewer, so an exact detached
     * portal is required for the client AABB A→B proof.
     */
    @Nullable
    public static Portal resolveHandoffPortal(
        UUID portalId, String portalNbtB64, ClientLevel sourceLevel
    ) {
        Portal live = findPortal(sourceLevel, portalId);
        return live != null ? live : surrogate(new SessionPortal(portalId, portalNbtB64), sourceLevel);
    }

    /** Drop an unreferenced handoff surrogate after its pending frame switch commits. */
    public static void releaseHandoffPortal() {
        discardUnreferencedSurrogates();
    }

    /** Starts a handoff visual tail before client A→B confirmation. */
    public static void beginHandoffVisual(UUID shipId, UUID portalId, String portalNbtB64) {
        SessionPortal next = new SessionPortal(portalId, portalNbtB64, true);
        if (!next.equals(HANDOFF_VISUALS.put(shipId, next))) {
            IplStraddleRenderCache.invalidateActivePasses();
        }
    }

    /** Removes a completed or superseded handoff visual tail. */
    public static void clearHandoffVisual(UUID shipId) {
        if (HANDOFF_VISUALS.remove(shipId) != null) {
            discardUnreferencedSurrogates();
            IplStraddleRenderCache.invalidateActivePasses();
        }
    }

    /** Discards a tail whose client object never materialized. */
    public static void clearAllVisualState(UUID shipId) {
        HANDOFF_VISUALS.remove(shipId);
        SESSIONS.remove(shipId);
        FUTURE.remove(shipId);
        RETIRED.remove(shipId);
        RETIRED_END_TICKS.remove(shipId);
        discardUnreferencedSurrogates();
        IplClientVisualTransitLatch.clear(shipId);
        IplStraddleRenderCache.invalidateActivePasses();
    }

    public static void clear() {
        Set<UUID> ids = new HashSet<>(SESSIONS.keySet());
        ids.addAll(RETIRED.keySet());
        ids.addAll(HANDOFF_VISUALS.keySet());
        for (UUID id : ids) IplClientVisualTransitLatch.clear(id);
        SESSIONS.clear();
        FUTURE.clear();
        RETIRED.clear();
        RETIRED_END_TICKS.clear();
        HANDOFF_VISUALS.clear();
        SURROGATES.clear();
        SURROGATE_PAYLOADS.clear();
        IplClientVisualTransitLatch.clearAll();
        IplStraddleRenderCache.invalidateActivePasses();
    }

    /** Called only after all poses have been rebased into this exact chart. */
    public static void enterFrame(UUID id, long frame) {
        SESSIONS.remove(id);
        RETIRED.remove(id);
        RETIRED_END_TICKS.remove(id);
        HANDOFF_VISUALS.remove(id);
        var future = FUTURE.get(id);
        if (future != null) {
            DeferredSnapshot snapshot = future.get(frame);
            future.headMap(frame, true).clear();
            if (future.isEmpty()) FUTURE.remove(id);
            if (snapshot != null) applySnapshot(id, snapshot.payload(), snapshot.endTick());
        }
        discardUnreferencedSurrogates();
        IplStraddleRenderCache.invalidateActivePasses();
    }

    /** ALL resolvable session portals for this ship, session-start order (multi-straddle). */
    public static List<Portal> resolveAllPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return List.of();
        }
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return List.of();
        if (portals.isEmpty()) return List.of();
        List<Portal> out = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    /** Render-only counterpart of {@link #resolvePortal}; includes a delayed exit tail. */
    @Nullable
    public static Portal resolveRenderPortal(ClientSubLevel sub) {
        if (sub == null) return null;
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return null;
        }
        List<Portal> portals = resolveRenderPortals(sub, level);
        return portals.isEmpty() ? null : portals.get(0);
    }

    /** Render-only counterpart of {@link #resolveAllPortals}; never use for collision. */
    public static List<Portal> resolveAllRenderPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return List.of();
        }
        return resolveRenderPortals(sub, level);
    }

    private static List<Portal> resolveRenderPortals(ClientSubLevel sub, ClientLevel level) {
        List<SessionPortal> portals = portalsForRender(sub, level);
        List<SessionPortal> active = SESSIONS.get(sub.getUniqueId());
        List<Portal> out = new ArrayList<>(portals.size() + 1);
        List<Portal> visibleActive = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved == null) continue;

            // A server session establishes parity, but its physics pose is ahead of Sable's
            // delayed render stream. Do not expose the split until this geometry reaches
            // the aperture; prediction below supplies the pre-RPC fast path.
            boolean isActive = active != null && containsPortal(active, sessionPortal.portalId());
            if (isActive && !IplClientVisualTransitLatch.isVisible(sub, resolved, sessionPortal.completed())) continue;
            if (isActive) visibleActive.add(resolved);
            out.add(resolved);
        }
        SessionPortal handoffPortal = HANDOFF_VISUALS.get(sub.getUniqueId());
        if (handoffPortal != null && !containsPortal(portals, handoffPortal.portalId())) {
            Portal live = findPortal(level, handoffPortal.portalId());
            Portal resolvedHandoff = live != null ? live : surrogate(handoffPortal, level);
            if (resolvedHandoff != null && IplClientVisualTransitLatch.isVisible(sub, resolvedHandoff, true)) {
                out.add(resolvedHandoff);
            }
        }
        return IplClientVisualTransitLatch.append(sub, level, visibleActive, out);
    }

    /** Diagnostic: how a ship's session portal currently resolves. */
    public static String debugPortalKind(ClientSubLevel sub) {
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        List<SessionPortal> retired = RETIRED.get(sub.getUniqueId());
        SessionPortal handoff = HANDOFF_VISUALS.get(sub.getUniqueId());
        if (portals == null && retired == null && handoff == null) return "no-session";
        Portal resolved = resolveRenderPortal(sub);
        int count = (portals == null ? 0 : portals.size()) + (retired == null ? 0 : retired.size())
            + (handoff == null ? 0 : 1);
        if (resolved == null) return "session-UNRESOLVED(" + count + ")";
        return SURROGATES.get(resolved.getUUID()) == resolved
            ? "surrogate:" + resolved.getUUID() : "live:" + resolved.getUUID();
    }

    /** A retired session is geometry AND time bounded, never an infinite half-space. */
    private static boolean stillRenderingThroughHalf(ClientSubLevel sub, Portal portal, int endTick, boolean completed) {
        var container = dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer.getContainer(sub.getLevel());
        if (container == null) return false;
        var clock = container.getInterpolation();
        if (clock.lastInterpolationTick >= endTick || clock.isStopped()
            && clock.getTickPointer() >= endTick + 1.5 - clock.getInterpolationDelay() - 1.0e-8) return false;
        return IplClientVisualTransitLatch.isVisible(sub, portal, completed);
    }

    /** Active sessions plus only the retired sessions still present in the delayed pose. */
    private static List<SessionPortal> portalsForRender(ClientSubLevel sub, ClientLevel level) {
        List<SessionPortal> active = SESSIONS.get(sub.getUniqueId());
        List<SessionPortal> retired = RETIRED.get(sub.getUniqueId());
        if (retired == null || retired.isEmpty()) {
            return active == null ? List.of() : active;
        }

        List<SessionPortal> visibleRetired = new ArrayList<>(retired.size());
        for (SessionPortal sessionPortal : retired) {
            Portal portal = findPortal(level, sessionPortal.portalId());
            if (portal == null) portal = surrogate(sessionPortal, level);
            int endTick = RETIRED_END_TICKS.getOrDefault(sub.getUniqueId(), Map.of())
                .getOrDefault(sessionPortal.portalId(), Integer.MIN_VALUE);
            if (portal != null && stillRenderingThroughHalf(sub, portal, endTick, sessionPortal.completed())) {
                visibleRetired.add(sessionPortal);
            }
        }
        if (visibleRetired.isEmpty()) {
            RETIRED.remove(sub.getUniqueId(), retired);
            RETIRED_END_TICKS.remove(sub.getUniqueId());
            discardUnreferencedSurrogates();
            return active == null ? List.of() : active;
        }
        if (visibleRetired.size() != retired.size()) {
            RETIRED.replace(sub.getUniqueId(), retired, List.copyOf(visibleRetired));
        }
        if (active == null || active.isEmpty()) return visibleRetired;

        List<SessionPortal> combined = new ArrayList<>(active.size() + visibleRetired.size());
        combined.addAll(active);
        for (SessionPortal sessionPortal : visibleRetired) {
            if (!containsPortal(active, sessionPortal.portalId())) combined.add(sessionPortal);
        }
        return combined;
    }

    private static boolean containsPortal(List<SessionPortal> portals, UUID portalId) {
        for (SessionPortal portal : portals) {
            if (portal.portalId().equals(portalId)) return true;
        }
        return false;
    }

    /** Called by the atomic mapped-pose parent handoff; old-frame tails are invalid there. */
    public static void clearRetiredForHandoff(UUID shipId) {
        RETIRED_END_TICKS.remove(shipId);
        if (RETIRED.remove(shipId) != null) discardUnreferencedSurrogates();
    }

    private static void discardUnreferencedSurrogates() {
        Set<UUID> referenced = new HashSet<>();
        collectReferencedPortals(SESSIONS, referenced);
        collectReferencedPortals(RETIRED, referenced);
        for (SessionPortal portal : HANDOFF_VISUALS.values()) referenced.add(portal.portalId());
        SURROGATES.keySet().retainAll(referenced);
        SURROGATE_PAYLOADS.keySet().retainAll(referenced);
    }

    private static void collectReferencedPortals(
        Map<UUID, List<SessionPortal>> sessions, Set<UUID> referenced
    ) {
        for (List<SessionPortal> list : sessions.values()) {
            for (SessionPortal sessionPortal : list) referenced.add(sessionPortal.portalId());
        }
    }

    @Nullable
    private static Portal findPortal(ClientLevel level, UUID portalId) {
        // ClientLevel's UUID entity index is protected; iteration is what the old
        // candidate collector did every frame anyway, and portals are few.
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Portal portal && !portal.isRemoved()
                && portal.getUUID().equals(portalId)) {
                return portal;
            }
        }
        // Dimension-stack seams are global portals: never in the entity list.
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (portal.getUUID().equals(portalId) && !portal.isRemoved()) return portal;
        }
        return null;
    }

    /** Global-portal-style reconstruction: type + NBT → detached Portal data carrier. */
    @Nullable
    private static Portal surrogate(SessionPortal sessionPortal, ClientLevel level) {
        Portal cached = SURROGATES.get(sessionPortal.portalId());
        if (cached != null && cached.level() == level
            && sessionPortal.portalNbtB64().equals(SURROGATE_PAYLOADS.get(sessionPortal.portalId()))) return cached;
        if (sessionPortal.portalNbtB64().isEmpty()) return null;
        try {
            String snbt = new String(
                Base64.getDecoder().decode(sessionPortal.portalNbtB64()), StandardCharsets.UTF_8);
            CompoundTag tag = TagParser.parseTag(snbt);
            // A future session can arrive before its queued parent flip. Its geometry
            // must not be reconstructed in the current/source dimension.
            if (tag.contains("ipl_source_dimension") && !tag.getString("ipl_source_dimension")
                .equals(level.dimension().location().toString())) return null;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.parse(tag.getString("entity_type")));
            Entity entity = type.create(level);
            if (entity == null) return null;
            entity.load(tag);
            entity.setUUID(sessionPortal.portalId());
            Portal portal = (Portal) entity;
            portal.updateCache();
            SURROGATES.put(sessionPortal.portalId(), portal);
            SURROGATE_PAYLOADS.put(sessionPortal.portalId(), sessionPortal.portalNbtB64());
            return portal;
        } catch (Throwable t) {
            LOG.error("[IPL-STRADDLE-SYNC] surrogate build failed for {}",
                sessionPortal.portalId(), t);
            return null;
        }
    }

    private static void applySnapshot(UUID shipId, String portalPayload, int endTick) {
        List<SessionPortal> previous = SESSIONS.get(shipId);
        List<SessionPortal> parsed = List.of();
        if (portalPayload == null || portalPayload.isEmpty()) {
            SESSIONS.remove(shipId);
        } else {
            List<SessionPortal> next = new ArrayList<>(2);
            for (String part : portalPayload.split(";")) {
                if (part.isEmpty()) continue;
                int sep = part.indexOf(':');
                if (sep < 0) {
                    next.add(new SessionPortal(UUID.fromString(part), ""));
                } else {
                    String encoded = part.substring(sep + 1);
                    boolean completed = false;
                    if (!encoded.isEmpty()) {
                        try {
                            CompoundTag tag = TagParser.parseTag(new String(
                                Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                            completed = tag.getBoolean("ipl_session_completed");
                        } catch (Exception e) {
                            LOG.warn("[IPL-STRADDLE-SYNC] invalid session geometry for {}", shipId, e);
                            continue;
                        }
                    }
                    next.add(new SessionPortal(UUID.fromString(part.substring(0, sep)), encoded, completed));
                }
            }
            parsed = List.copyOf(next);
            if (parsed.isEmpty()) {
                SESSIONS.remove(shipId);
            } else {
                SESSIONS.put(shipId, parsed);
            }
        }

        // Keep an ended split only until the delayed render pose clears its plane.
        // It covers both native eye-space and the portal render pass without keeping
        // a retired Atlas image collider alive on the server.
        if (previous != null) {
            List<SessionPortal> retired = new ArrayList<>(previous.size());
            List<SessionPortal> alreadyRetired = RETIRED.get(shipId);
            if (alreadyRetired != null) retired.addAll(alreadyRetired);
            for (SessionPortal old : previous) {
                if (!containsPortal(parsed, old.portalId())
                    && !containsPortal(retired, old.portalId())) {
                    retired.add(old);
                    RETIRED_END_TICKS.computeIfAbsent(shipId, ignored -> new ConcurrentHashMap<>())
                        .put(old.portalId(), endTick);
                }
            }
            if (!retired.isEmpty()) RETIRED.put(shipId, List.copyOf(retired));
        }
        List<SessionPortal> oldRetired = RETIRED.get(shipId);
        if (oldRetired != null && !parsed.isEmpty()) {
            List<SessionPortal> remaining = new ArrayList<>(oldRetired.size());
            for (SessionPortal retired : oldRetired) {
                if (!containsPortal(parsed, retired.portalId())) remaining.add(retired);
                else {
                    var ticks = RETIRED_END_TICKS.get(shipId);
                    if (ticks != null) ticks.remove(retired.portalId());
                }
            }
            if (remaining.isEmpty()) RETIRED.remove(shipId, oldRetired);
            else if (remaining.size() != oldRetired.size()) {
                RETIRED.replace(shipId, oldRetired, List.copyOf(remaining));
            }
        }
        discardUnreferencedSurrogates();

        // Straddle decisions are cached per frame; drop them so this snapshot
        // takes effect within the same client tick it arrives in.
        IplStraddleRenderCache.invalidateActivePasses();
    }

    public static final class RemoteCallables {

        /**
         * Full per-ship snapshot: ';'-joined {@code portalUuid:base64Nbt} entries,
         * empty = no sessions.
         */
        public static void snapshot(String shipUuid, String portalPayload, String completedTick, String coordinateFrame) {
            try {
                UUID shipId = UUID.fromString(shipUuid);
                var hosting = IplClientHostedLookup.getHostingContainerOrNull();
                // Broadcasts include untracked ships. Their track-start bootstrap sends
                // the current frame/state; retaining every unknown loop epoch leaks memory.
                if (hosting == null || hosting.getSubLevel(shipId) == null) return;
                int endTick = Integer.parseInt(completedTick);
                long frame = Long.parseLong(coordinateFrame);
                if (frame < IplParentDimSync.frame(shipId)) return;
                if (frame > IplParentDimSync.frame(shipId)) {
                    FUTURE.computeIfAbsent(shipId, ignored -> new java.util.TreeMap<>())
                        .put(frame, new DeferredSnapshot(portalPayload, endTick));
                    return;
                }
                applySnapshot(shipId, portalPayload, endTick);
            } catch (Throwable t) {
                LOG.error("[IPL-STRADDLE-SYNC] bad snapshot for {}", shipUuid, t);
            }
        }
    }
}
