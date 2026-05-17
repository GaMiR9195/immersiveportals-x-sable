package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick orchestrator: scans every {@link ServerSubLevel} in a
 * {@link ServerSubLevelContainer} for portal crossings, dispatches transit when
 * the crossing condition is met.
 *
 * <p>Invoked from {@code SableSubLevelTransitMixin} at the TAIL of
 * {@code ServerSubLevelContainer.tick}, so by the time we run:
 * <ul>
 *   <li>Physics has completed for this tick (pose has been updated from pipeline).</li>
 *   <li>{@code lastPose} reflects the start-of-tick position;
 *       {@code logicalPose} reflects the end-of-tick position.</li>
 * </ul>
 * That's the right window for "did the airship cross the portal this tick" detection.
 *
 * <p>Phase 1: atomic teleport. Phase 2 will extend to spawn a kinematic mirror in
 * the dest dim before crossing (approach detection rather than crossing detection).
 */
public final class SableTransitController {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    /** Inflation amount when querying for nearby portals -- how close before we consider one. */
    private static final double PORTAL_QUERY_INFLATION = 4.0;

    /**
     * Per-airship cooldown in server ticks. After a transit fires for an airship UUID,
     * we ignore further transit conditions for that UUID for this many ticks. Prevents
     * rapid-fire oscillation if velocity transfer doesn't fully push the airship past
     * the portal plane, or if some other edge case causes the airship to drift back.
     *
     * <p>2 seconds (40 ticks) is well over the time it'd take for any reasonable airship
     * velocity to clear a portal volume, and short enough that intentional repeated
     * passes (e.g., player flying the airship through a portal multiple times) still
     * feel responsive.
     */
    private static final long TRANSIT_COOLDOWN_TICKS = 40L;

    /**
     * Last server tick at which a transit fired for a given airship UUID. Used to
     * enforce the cooldown above. Entries are pruned lazily when they age out.
     *
     * <p>This is per-process, not per-dim, because UUIDs are unique and an airship
     * coming back through a portal (returning to source dim) is exactly the case we
     * want to ratelimit.
     */
    private static final Map<UUID, Long> lastTransitTick = new HashMap<>();

    private SableTransitController() {}

    /**
     * Called once per server tick per dimension's container, at the TAIL of
     * {@code ServerSubLevelContainer.tick}.
     */
    public static void onContainerTick(ServerSubLevelContainer container) {
        ServerLevel level = (ServerLevel) container.getLevel();
        if (level == null) return;
        long nowTick = level.getGameTime();

        // Lazy prune of the cooldown map: drop entries older than the cooldown window.
        // Keeps the map bounded even after server uptime grows.
        pruneCooldownMap(nowTick);

        // Track which (sourceUuid, portalUuid) pairs are still active mirrors this
        // tick -- so we can despawn any mirror whose source is no longer in the
        // approach zone after we finish the iteration.
        java.util.Set<MirrorRegistry.MirrorKey> seenMirrors = new java.util.HashSet<>();

        // Collect transit candidates first so we don't mutate iterators while iterating
        // (transit will call container.removeSubLevel which mutates allSubLevels).
        List<TransitCandidate> candidates = null;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel airship)) continue;
            if (airship.isRemoved()) continue;

            // Skip airships that are themselves kinematic mirrors. Mirrors don't
            // initiate transits; they're driven by their source.
            if (airship instanceof ipl.sable.iface.IplKinematicSubLevelHolder kh
                && kh.ipl$isKinematicMirror()) {
                continue;
            }

            // Cooldown check: skip airships that just transited.
            Long lastTick = lastTransitTick.get(airship.getUniqueId());
            if (lastTick != null && (nowTick - lastTick) < TRANSIT_COOLDOWN_TICKS) {
                continue;
            }

            // Convert Sable's BoundingBox3d to MC AABB for the portal query.
            AABB airshipAabb = airship.boundingBox().toMojang().inflate(PORTAL_QUERY_INFLATION);
            List<Portal> nearby = level.getEntitiesOfClass(
                Portal.class,
                airshipAabb,
                Portal::isTeleportable
            );

            boolean candidateAddedForAirship = false;
            for (Portal portal : nearby) {
                if (PortalCrossingDetector.didCrossThisTick(airship, portal)) {
                    if (candidates == null) candidates = new ArrayList<>(1);
                    candidates.add(new TransitCandidate(airship, portal));
                    candidateAddedForAirship = true;
                    // Only fire one transit per airship per tick -- if the airship
                    // overlaps multiple portals, we pick the first crossing detected.
                    break;
                }
            }

            // Mirror lifecycle: for each nearby portal NOT in a crossing state this
            // tick, spawn-or-sync the mirror for that (source, portal) pair. (If we
            // just queued a crossing transit, skip mirror handling -- the transit
            // executor will despawn the mirror as part of handoff.)
            if (!candidateAddedForAirship) {
                for (Portal portal : nearby) {
                    UUID portalUuid = portal.getUUID();
                    MirrorRegistry.MirrorKey key = new MirrorRegistry.MirrorKey(
                        airship.getUniqueId(), portalUuid
                    );
                    MirrorRegistry.MirrorEntry entry = MirrorRegistry.get(
                        airship.getUniqueId(), portalUuid
                    );
                    try {
                        if (entry == null) {
                            // First time approach -- spawn a mirror in the dest dim.
                            ServerSubLevel spawned = MirrorOps.spawnMirror(airship, portal);
                            if (spawned != null) {
                                seenMirrors.add(key);
                            }
                        } else {
                            // Existing mirror -- sync its pose from source via portal transform.
                            boolean ok = MirrorOps.syncMirrorPose(airship, portal, entry);
                            if (ok) {
                                seenMirrors.add(key);
                            } else {
                                // Sync failed (dest dim unloaded? mirror removed externally?).
                                // Drop the registry entry; will despawn on next tick if it still
                                // exists in dest container.
                                MirrorRegistry.remove(airship.getUniqueId(), portalUuid);
                            }
                        }
                    } catch (Throwable t) {
                        LOG.error("[IPL-MIRROR] uncaught exception handling mirror for {}/{}",
                            airship.getUniqueId(), portalUuid, t);
                    }
                }
            }
        }

        // Despawn mirrors whose source is no longer in any portal's approach zone.
        // Iterate a snapshot so we can remove during the loop.
        for (MirrorRegistry.MirrorEntry entry : new ArrayList<>(MirrorRegistry.all())) {
            // Only consider mirrors whose source is in THIS dim (the container we're ticking).
            if (!entry.sourceDim().equals(container.getLevel().dimension())) continue;
            MirrorRegistry.MirrorKey key = new MirrorRegistry.MirrorKey(
                entry.sourceUuid(), entry.portalUuid()
            );
            if (!seenMirrors.contains(key)) {
                MirrorOps.despawnMirror(entry, level.getServer());
            }
        }

        if (candidates == null) return;

        for (TransitCandidate c : candidates) {
            UUID uuid = c.airship.getUniqueId();
            try {
                // Before atomic teleport, despawn any mirror for this (source, portal)
                // pair so we don't end up with mirror + new dest sub-level coexisting.
                // Phase 2 C2 will replace this with proper handoff (mirror promoted in
                // place); for now we're degrading to Phase 1 at the moment of crossing.
                MirrorRegistry.MirrorEntry mirrorEntry = MirrorRegistry.get(
                    uuid, c.portal.getUUID()
                );
                if (mirrorEntry != null) {
                    MirrorOps.despawnMirror(mirrorEntry, level.getServer());
                }

                boolean transited = SableTransitOps.executeTransit(c.airship, c.portal);
                if (transited) {
                    lastTransitTick.put(uuid, nowTick);
                }
            } catch (Throwable t) {
                LOG.error("[IPL-TRANSIT] uncaught exception executing transit for uuid={}",
                    uuid, t);
                // Even on failure, set cooldown -- prevents the same failing transit
                // from retrying every tick and flooding logs.
                lastTransitTick.put(uuid, nowTick);
            }
        }
    }

    private static void pruneCooldownMap(long nowTick) {
        Iterator<Map.Entry<UUID, Long>> iter = lastTransitTick.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, Long> e = iter.next();
            if ((nowTick - e.getValue()) >= TRANSIT_COOLDOWN_TICKS) {
                iter.remove();
            }
        }
    }

    private record TransitCandidate(ServerSubLevel airship, Portal portal) {}
}
