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

    private record SessionPortal(UUID portalId, String portalNbtB64) {}

    /** Ship → active session portals (session-start order; empty never stored). */
    private static final ConcurrentMap<UUID, List<SessionPortal>> SESSIONS = new ConcurrentHashMap<>();

    /** Detached surrogate portals built from snapshot NBT, by portal id. */
    private static final ConcurrentMap<UUID, Portal> SURROGATES = new ConcurrentHashMap<>();

    private IplStraddleSessionStore() {}

    /**
     * The authoritative straddle portal for this ship, or null when the server has no
     * session for it. Live entity in the parent level when synced; snapshot surrogate
     * otherwise.
     */
    @Nullable
    public static Portal resolvePortal(ClientSubLevel sub) {
        if (sub == null) return null;
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return null;
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return null;
        }
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

    /** ALL resolvable session portals for this ship, session-start order (multi-straddle). */
    public static List<Portal> resolveAllPortals(ClientSubLevel sub) {
        if (sub == null) return List.of();
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return List.of();
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return List.of();
        }
        List<Portal> out = new ArrayList<>(portals.size());
        for (SessionPortal sessionPortal : portals) {
            Portal live = findPortal(level, sessionPortal.portalId());
            Portal resolved = live != null ? live : surrogate(sessionPortal, level);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    /** Diagnostic: how a ship's session portal currently resolves. */
    public static String debugPortalKind(ClientSubLevel sub) {
        List<SessionPortal> portals = SESSIONS.get(sub.getUniqueId());
        if (portals == null) return "no-session";
        Portal resolved = resolvePortal(sub);
        if (resolved == null) return "session-UNRESOLVED(" + portals.size() + ")";
        return SURROGATES.get(resolved.getUUID()) == resolved
            ? "surrogate:" + resolved.getUUID() : "live:" + resolved.getUUID();
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
        if (cached != null && cached.level() == level) return cached;
        if (sessionPortal.portalNbtB64().isEmpty()) return null;
        try {
            String snbt = new String(
                Base64.getDecoder().decode(sessionPortal.portalNbtB64()), StandardCharsets.UTF_8);
            CompoundTag tag = TagParser.parseTag(snbt);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.parse(tag.getString("entity_type")));
            Entity entity = type.create(level);
            if (entity == null) return null;
            entity.load(tag);
            entity.setUUID(sessionPortal.portalId());
            Portal portal = (Portal) entity;
            portal.updateCache();
            SURROGATES.put(sessionPortal.portalId(), portal);
            return portal;
        } catch (Throwable t) {
            LOG.error("[IPL-STRADDLE-SYNC] surrogate build failed for {}",
                sessionPortal.portalId(), t);
            return null;
        }
    }

    public static final class RemoteCallables {

        /**
         * Full per-ship snapshot: ';'-joined {@code portalUuid:base64Nbt} entries,
         * empty = no sessions.
         */
        public static void snapshot(String shipUuid, String portalPayload) {
            try {
                UUID shipId = UUID.fromString(shipUuid);
                if (portalPayload == null || portalPayload.isEmpty()) {
                    SESSIONS.remove(shipId);
                } else {
                    List<SessionPortal> parsed = new ArrayList<>(2);
                    for (String part : portalPayload.split(";")) {
                        if (part.isEmpty()) continue;
                        int sep = part.indexOf(':');
                        if (sep < 0) {
                            parsed.add(new SessionPortal(UUID.fromString(part), ""));
                        } else {
                            parsed.add(new SessionPortal(
                                UUID.fromString(part.substring(0, sep)), part.substring(sep + 1)));
                        }
                    }
                    if (parsed.isEmpty()) {
                        SESSIONS.remove(shipId);
                    } else {
                        SESSIONS.put(shipId, List.copyOf(parsed));
                    }
                }

                // Retain only surrogates still referenced by some ship's session set.
                Set<UUID> referenced = new HashSet<>();
                for (List<SessionPortal> list : SESSIONS.values()) {
                    for (SessionPortal sp : list) referenced.add(sp.portalId());
                }
                SURROGATES.keySet().retainAll(referenced);

                // Straddle decisions are cached per frame; drop them so this snapshot
                // takes effect within the same client tick it arrives in.
                IplStraddleRenderCache.invalidateActivePasses();
            } catch (Throwable t) {
                LOG.error("[IPL-STRADDLE-SYNC] bad snapshot for {}", shipUuid, t);
            }
        }
    }
}
