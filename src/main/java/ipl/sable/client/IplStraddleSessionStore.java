package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client mirror of the server's straddle sessions (see
 * {@code IplStraddleSessionSync}): per ship, the portal ids of its active straddle
 * sessions, replaced wholesale by each snapshot RPC.
 *
 * <p>This store carries ONLY parity — which portal face the ship is crossing, hence
 * which half is "still here". Plane geometry is rebuilt every frame from the resolved
 * portal entity's current transform, so moving portals stay smooth while parity can
 * never flicker: it changes exactly when the server's latch does.
 */
public final class IplStraddleSessionStore {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-session-store");

    /** Ship → active session portal ids (session-start order; empty never stored). */
    private static final ConcurrentMap<UUID, List<UUID>> SESSIONS = new ConcurrentHashMap<>();

    private IplStraddleSessionStore() {}

    /**
     * The authoritative straddle portal for this ship, resolved against its parent
     * level's current entities, or null when the server has no session for it (or the
     * portal entity isn't synced to this client yet — parity without geometry is not
     * actionable, so that reads as "no straddle" until the entity arrives).
     */
    @Nullable
    public static Portal resolvePortal(ClientSubLevel sub) {
        if (sub == null) return null;
        List<UUID> portalIds = SESSIONS.get(sub.getUniqueId());
        if (portalIds == null) return null;
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) {
            return null;
        }
        for (UUID portalId : portalIds) {
            Portal portal = findPortal(level, portalId);
            if (portal != null) return portal;
        }
        return null;
    }

    public static boolean hasSession(UUID shipId) {
        return SESSIONS.containsKey(shipId);
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

    public static final class RemoteCallables {

        /** Full per-ship snapshot: {@code portalUuids} is ';'-joined, empty = no sessions. */
        public static void snapshot(String shipUuid, String portalUuids) {
            try {
                UUID shipId = UUID.fromString(shipUuid);
                if (portalUuids == null || portalUuids.isEmpty()) {
                    SESSIONS.remove(shipId);
                } else {
                    String[] parts = portalUuids.split(";");
                    java.util.ArrayList<UUID> ids = new java.util.ArrayList<>(parts.length);
                    for (String part : parts) {
                        if (!part.isEmpty()) ids.add(UUID.fromString(part));
                    }
                    if (ids.isEmpty()) {
                        SESSIONS.remove(shipId);
                    } else {
                        SESSIONS.put(shipId, List.copyOf(ids));
                    }
                }
                // Straddle decisions are cached per frame; drop them so this snapshot
                // takes effect within the same client tick it arrives in.
                IplStraddleRenderCache.invalidateActivePasses();
            } catch (Throwable t) {
                LOG.error("[IPL-STRADDLE-SYNC] bad snapshot for {}", shipUuid, t);
            }
        }
    }
}
