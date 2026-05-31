package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

    /**
     * Per-(source, portal) mirror operation cooldown in server ticks. After a mirror
     * spawn OR despawn for a pair, ignore further spawn/sync conditions for that pair
     * for this many ticks. Prevents rapid respawn churn if something causes the
     * mirror to repeatedly fail sync (e.g., Sable invalidates the mass tracker
     * mid-tick, leaving us with a dead mirror and no valid registry entry).
     *
     * <p>Shorter than the transit cooldown (10 ticks vs 40) because mirrors are
     * transient by nature -- they spawn and despawn on approach/disengage. A pair
     * that's been mirror-cycled within the last half-second is suspicious and
     * deserves a breather.
     */
    private static final long MIRROR_COOLDOWN_TICKS = 10L;

    private static final Map<MirrorRegistry.MirrorKey, Long> lastMirrorOpTick = new HashMap<>();

    private SableTransitController() {}

    /**
     * Tear down all mirror state when the server is stopping. Despawns every
     * registered mirror (removing it from its dest container so its plot chunks
     * are freed) and clears all per-process static state.
     *
     * <p><b>Why this is needed -- two bugs it fixes:</b>
     * <ul>
     *   <li><b>Duplication on quit-to-title:</b> {@link MirrorRegistry} and the
     *       cooldown maps are {@code static}. In singleplayer the same JVM hosts
     *       successive integrated servers, so a mirror entry from session 1
     *       survives into session 2; the controller then treats it as live and
     *       spawns alongside it -> the "3 airships" the player counted.</li>
     *   <li><b>Shutdown chunk-drain hang:</b> a mirror left in its dest container
     *       at shutdown keeps its plot chunks occupied. {@code MinecraftServer.stopServer}'s
     *       chunk drain ({@code ServerChunkCache.tick -> ChunkMap.processUnloads})
     *       then spins waiting for those chunks to unload (watchdog 31May, stuck in
     *       vanilla {@code processUnloads} with no mod frames). Despawning before
     *       the drain frees them.</li>
     * </ul>
     *
     * <p>Called from {@link ipl.sable.mixin.IplServerStopMirrorCleanupMixin} at the
     * HEAD of {@code stopServer} -- before the final save and before the chunk drain.
     * Per-mirror failures are swallowed so one bad despawn can't abort the rest of
     * shutdown.
     */
    public static void onServerStopping(MinecraftServer server) {
        List<MirrorRegistry.MirrorEntry> snapshot = new ArrayList<>(MirrorRegistry.all());
        int despawned = 0;
        for (MirrorRegistry.MirrorEntry entry : snapshot) {
            try {
                MirrorOps.despawnMirror(entry, server);
                despawned++;
            } catch (Throwable t) {
                LOG.warn("[IPL-MIRROR] server-stop despawn failed for mirror={}", entry.mirrorUuid(), t);
            }
        }
        // Belt-and-suspenders: despawnMirror already removes each entry, but clear
        // the whole registry + cooldown maps so NOTHING carries into the next
        // integrated server in this JVM.
        MirrorRegistry.clear();
        lastMirrorOpTick.clear();
        lastTransitTick.clear();
        if (despawned > 0 || !snapshot.isEmpty()) {
            LOG.info("[IPL-MIRROR] server-stop cleanup: despawned {} mirror(s), cleared registry", despawned);
        }
    }

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

            // Defensive skip: a sub-level tagged "ipl_mirror" in user_data is
            // treated as a mirror regardless of the runtime @Unique kinematic
            // flag (which doesn't survive serialisation). This catches any
            // ghost mirror from a legacy save whose recovery mixin didn't
            // fire on this run -- preventing the spawn-mirror-for-ghost
            // cascade that produced the hang in the 29May log.
            net.minecraft.nbt.CompoundTag userData = airship.getUserDataTag();
            if (userData != null && userData.getBoolean("ipl_mirror")) {
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

            // Deduplicate portals by destination dim before spawning mirrors.
            // IP creates pairs of portals per frame -- a "main" Portal plus an
            // "intrinsic_diligent" companion, both isTeleportable=true, both
            // pointing to the same destDim. Without dedup we spawn one mirror per
            // companion entity, which shows up to the player as two visually
            // identical airships hovering side-by-side ("ghost copies"). Pick the
            // single portal closest to the airship per dest dim.
            List<Portal> nearbyDedup = pickClosestPortalPerDestDim(nearby, airship);

            // Mirror lifecycle: for each nearby portal NOT in a crossing state this
            // tick, spawn-or-sync the mirror for that (source, portal) pair. (If we
            // just queued a crossing transit, skip mirror handling -- the transit
            // executor will despawn the mirror as part of handoff.)
            if (!candidateAddedForAirship) {
                for (Portal portal : nearbyDedup) {
                    UUID portalUuid = portal.getUUID();
                    MirrorRegistry.MirrorKey key = new MirrorRegistry.MirrorKey(
                        airship.getUniqueId(), portalUuid
                    );

                    // Cooldown check on the (source, portal) pair. If we just spawned
                    // or despawned a mirror for this pair, give it a few ticks before
                    // doing anything. Prevents rapid respawn churn -> wasted CPU +
                    // possible visual artifacts.
                    Long lastOp = lastMirrorOpTick.get(key);
                    if (lastOp != null && (nowTick - lastOp) < MIRROR_COOLDOWN_TICKS) {
                        // Still in cooldown. If a mirror exists in the registry, count
                        // it as "seen" so the despawn-pass below doesn't kill it. If
                        // not, just skip.
                        if (MirrorRegistry.get(airship.getUniqueId(), portalUuid) != null) {
                            seenMirrors.add(key);
                        }
                        continue;
                    }

                    MirrorRegistry.MirrorEntry entry = MirrorRegistry.get(
                        airship.getUniqueId(), portalUuid
                    );
                    try {
                        if (entry == null) {
                            // First time approach -- spawn a mirror in the dest dim.
                            ServerSubLevel spawned = MirrorOps.spawnMirror(airship, portal);
                            if (spawned != null) {
                                seenMirrors.add(key);
                                lastMirrorOpTick.put(key, nowTick);
                            }
                        } else {
                            // Existing mirror -- sync its pose from source via portal transform.
                            boolean ok = MirrorOps.syncMirrorPose(airship, portal, entry);
                            if (ok) {
                                seenMirrors.add(key);
                            } else {
                                // Sync failed (mirror gone from dest container, dest dim
                                // unloaded, etc.). Run a proper despawn -- removes from
                                // registry AND emits the wrapped removeSubLevel so any
                                // client-side stale mirror gets cleaned up. Without this,
                                // we leave an orphaned mirror in the dest container that
                                // eventually gets auto-removed by Sable's
                                // processSubLevelRemovals (invalid mass tracker) which
                                // calls destroyAllBlocks -- dropping items.
                                MirrorOps.despawnMirror(entry, level.getServer());
                                lastMirrorOpTick.put(key, nowTick);
                            }
                        }
                    } catch (Throwable t) {
                        LOG.error("[IPL-MIRROR] uncaught exception handling mirror for {}/{}",
                            airship.getUniqueId(), portalUuid, t);
                        // Even on uncaught failure, set cooldown to prevent log spam.
                        lastMirrorOpTick.put(key, nowTick);
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
                lastMirrorOpTick.put(key, nowTick);
            }
        }

        // Prune mirror-cooldown map: drop entries older than the cooldown window so
        // the map stays bounded.
        var mirrorCdIter = lastMirrorOpTick.entrySet().iterator();
        while (mirrorCdIter.hasNext()) {
            var e = mirrorCdIter.next();
            if ((nowTick - e.getValue()) >= MIRROR_COOLDOWN_TICKS) {
                mirrorCdIter.remove();
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

    /**
     * Group nearby portals by their destination dimension and keep, per group,
     * only the portal whose origin is closest to the airship's AABB center.
     *
     * <p>Rationale: IP can spawn multiple {@link Portal} entities for a single
     * physical portal frame -- a "main" portal and one or more
     * {@code intrinsic_diligent_nether_portal} companions. All of them pass the
     * {@link Portal#isTeleportable} filter and all point to the same dest dim,
     * so the unfiltered {@code nearby} list double-counts. Spawning a mirror
     * per entity would show the player two (or more) visually-identical mirror
     * airships, indistinguishable to anyone not reading server logs.
     *
     * <p>For our purposes one mirror per destination dim is the right
     * semantics -- the user just wants to preview "where they'd end up." We
     * pick the geometrically-closest portal because that's the one whose
     * portal-mapped pose will be most visually accurate as the airship
     * approaches (the duplicates point through near-identical frames so the
     * picked one and the discarded one would produce nearly the same mirror
     * pose anyway; using "closest" gives a stable, well-defined choice).
     */
    private static List<Portal> pickClosestPortalPerDestDim(List<Portal> portals, ServerSubLevel airship) {
        if (portals.size() <= 1) return portals;

        Vec3 airshipCenter = airship.boundingBox().toMojang().getCenter();

        // ResourceKey<Level> is a value type -- safe to use as a map key.
        Map<ResourceKey<Level>, Portal> bestPerDest = new HashMap<>(4);
        Map<ResourceKey<Level>, Double> bestDistSqPerDest = new HashMap<>(4);

        for (Portal portal : portals) {
            ResourceKey<Level> destDim = portal.getDestDim();
            double distSq = portal.getOriginPos().distanceToSqr(airshipCenter);
            Double current = bestDistSqPerDest.get(destDim);
            if (current == null || distSq < current) {
                bestPerDest.put(destDim, portal);
                bestDistSqPerDest.put(destDim, distSq);
            }
        }

        return new ArrayList<>(bestPerDest.values());
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
