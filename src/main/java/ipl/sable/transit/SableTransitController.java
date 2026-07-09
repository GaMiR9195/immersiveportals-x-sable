package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
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

    /**
     * Per-(source, portal) locked source->dest normal for the crossing-phase
     * evaluation, held for the duration of a contact session. Distinct from the
     * client-side clip-normal tracker ({@code SubLevelPortalContactTracker}) on
     * purpose: that one stores the clip normal (oriented toward the KEPT/source
     * side) and is written from the render thread; this stores the opposite
     * source->dest direction and is written from the server thread. Sharing one
     * map would let the two sign conventions clobber each other in singleplayer
     * (same JVM, same UUID key). Server-thread only, so a plain HashMap is fine.
     *
     * <p>Locking matters because a free-rotating airship's AABB center can wobble
     * across the plane mid-straddle; without the lock the oriented normal could
     * flip and bounce the crossing phase. The entry is dropped when the pair is no
     * longer near the portal (see the despawn/cleanup pass).
     */
    private static final Map<MirrorRegistry.MirrorKey, Vec3> lockedCrossingNormal = new HashMap<>();

    /**
     * Dim-agnostic straddle latch. In the legacy model the mirror's existence is the latch
     * ("only swap a pair that previously straddled"); hosted sub-levels have no mirrors, so the
     * latch is explicit: a (subLevel, portal) key enters on STRADDLING, leaves on APPROACHING
     * (ship backed out) or on the CROSSED flip. Server-thread only.
     */
    private static final java.util.Set<MirrorRegistry.MirrorKey> hostedStraddleLatch = new java.util.HashSet<>();

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
        lockedCrossingNormal.clear();
        hostedStraddleLatch.clear();
        IplStraddleCloneBody.clearAll();
        IplStraddleTerrainClone.clearAll();
        SableRehomeOps.resetBootRestore();
        ipl.sable.dim.IplSceneOwnership.clearAll();
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

        // Per-scene model: migrate any hosted body whose scene doesn't match its parent —
        // covers boot-restored ships whose parent resolved after the body was created
        // (fallback landed it in the hosting scene) and any missed flip path.
        if (ipl.sable.dim.IplSceneOwnership.isEnabled()
            && ipl.sable.dim.IplDimAgnostic.isHostingLevel(level)) {
            ipl.sable.dim.IplSceneOwnership.reconcile(container);
        }

        // Track which (sourceUuid, portalUuid) pairs are still active mirrors this
        // tick -- so we can despawn any mirror whose source is no longer in the
        // approach zone after we finish the iteration.
        java.util.Set<MirrorRegistry.MirrorKey> seenMirrors = new java.util.HashSet<>();

        // Same idea for hosted (dim-agnostic) pairs: latch + locked-normal entries whose pair
        // is no longer near a portal get reaped after the loop. Only meaningful when ticking
        // the hosting container (all hosted ships live there).
        java.util.Set<MirrorRegistry.MirrorKey> seenHostedKeys = new java.util.HashSet<>();

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

            // Self-heal stale physics-actor registrations. After a transit the dest chunk's
            // block entities can be recreated by a chunk reprocess that doesn't re-fire
            // plot.onBlockChange, leaving Sable's actor registry pointing at the old,
            // orphaned BE (frozen rotationSpeed -> dead propeller/wheel-drive). This rebinds
            // any stale actor to the live chunk BE. No-op (pure identity checks) for healthy
            // airships, so it's safe to run every tick for every real sub-level.
            int rebound = SableTransitOps.resyncStaleActors(airship);
            if (rebound > 0) {
                LOG.info("[IPL-TRANSIT] rebound {} stale physics actor(s) to live block "
                    + "entities for uuid={}", rebound, airship.getUniqueId());
            }

            // Cooldown check: skip airships that just transited.
            Long lastTick = lastTransitTick.get(airship.getUniqueId());
            if (lastTick != null && (nowTick - lastTick) < TRANSIT_COOLDOWN_TICKS) {
                continue;
            }

            // Dim-agnostic model: the legacy mirror/copy machinery is disabled. Un-hosted
            // ships are pending rehome (SableRehomeOps.sweep handles them within a tick);
            // hosted ships query portals in their PARENT dim and transit via parent flip.
            boolean hosted = IplDimAgnostic.isHosted(airship);
            if (IplDimAgnostic.isEnabled() && !hosted) {
                continue;
            }

            ServerLevel portalQueryLevel = level;
            if (hosted) {
                portalQueryLevel = IplDimAgnostic.getServerParentLevel(airship);
                if (portalQueryLevel == null) continue;
            }

            // Convert Sable's BoundingBox3d to MC AABB for the portal query.
            AABB airshipAabb = airship.boundingBox().toMojang().inflate(PORTAL_QUERY_INFLATION);
            List<Portal> nearby = portalQueryLevel.getEntitiesOfClass(
                Portal.class,
                airshipAabb,
                Portal::isTeleportable
            );

            // Dimension-stack seams (VerticalConnectingPortal & friends) are GLOBAL portals —
            // held in GlobalPortalStorage, never returned by entity queries. Include any whose
            // (rect-clamped) nearest point reaches the inflated ship box. Distance-to-center vs
            // the box's half-diagonal matches the entity query's reach semantics closely enough.
            if (IplDimAgnostic.isEnabled()) {
                Vec3 shipCenter = airshipAabb.getCenter();
                double reach = 0.5 * Math.sqrt(
                    airshipAabb.getXsize() * airshipAabb.getXsize()
                        + airshipAabb.getYsize() * airshipAabb.getYsize()
                        + airshipAabb.getZsize() * airshipAabb.getZsize());
                for (Portal globalPortal :
                    qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage
                        .getGlobalPortals(portalQueryLevel)) {
                    if (globalPortal.isTeleportable()
                        && globalPortal.getDistanceToNearestPointInPortal(shipCenter) <= reach) {
                        nearby.add(globalPortal);
                    }
                }
            }

            // Deduplicate portals by destination dim. IP creates pairs of portals
            // per frame -- a "main" Portal plus an "intrinsic_diligent" companion,
            // both isTeleportable=true, both pointing to the same destDim. Without
            // dedup we'd spawn one mirror per companion ("ghost copies"). Pick the
            // single portal closest to the airship per dest dim.
            List<Portal> nearbyDedup = pickClosestPortalPerDestDim(nearby, airship);

            // Edge-interval crossing model (replaces the old centroid-crossing +
            // proximity-spawn gates). For each nearby portal we project the airship's
            // OBB onto a per-session-locked source->dest normal and classify:
            //   APPROACHING -> hide mirror (leading edge hasn't reached the plane)
            //   STRADDLING  -> spawn/sync mirror (leading edge through, trailing not)
            //   CROSSED     -> if a mirror exists (it straddled and is now fully
            //                  through), swap; the mirror becomes the source.
            // The mirror's existence IS the straddle latch: we only ever swap a
            // (source, portal) pair that previously straddled and spawned a mirror,
            // so a ship that backs out, or one teleported fully across without
            // straddling, never spuriously swaps.
            boolean candidateAddedForAirship = false;
            for (Portal portal : nearbyDedup) {
                UUID portalUuid = portal.getUUID();
                MirrorRegistry.MirrorKey key = new MirrorRegistry.MirrorKey(
                    airship.getUniqueId(), portalUuid
                );

                Vec3 normal = ipl$lockedSourceToDestNormal(airship, portal);
                PortalCrossingDetector.CrossingState state =
                    PortalCrossingDetector.evaluate(airship, portal, normal);

                // Bring-up probe (remove later): log every phase evaluation that is either
                // non-trivial or follows a latched straddle — the 1-tick clone-session
                // mystery lives in this decision.
                if (hosted && (state.phase() != PortalCrossingDetector.CrossingPhase.APPROACHING
                    || hostedStraddleLatch.contains(key))) {
                    LOG.info("[IPL-PHASE] uuid={} portal={} phase={} lead={} trail={} nearby={} latched={}",
                        airship.getUniqueId().toString().substring(0, 8),
                        portalUuid.toString().substring(0, 8),
                        state.phase(),
                        String.format("%.2f", state.leadingEdge()),
                        String.format("%.2f", state.trailingEdge()),
                        nearbyDedup.size(),
                        hostedStraddleLatch.contains(key));
                }

                // Hosted (dim-agnostic) dispatch: no mirrors. The explicit straddle latch
                // replaces "mirror exists" as the only-swap-after-straddle guard; CROSSED
                // resolves to a parent flip via SableRehomeOps.executeHostedTransit.
                if (hosted) {
                    switch (state.phase()) {
                        case CROSSED -> {
                            if (hostedStraddleLatch.remove(key)) {
                                // Clone must despawn BEFORE the transit migrates the real
                                // body into the dest scene (their plot sections share keys).
                                IplStraddleCloneBody.clear(key, "crossed");
                                IplStraddleTerrainClone.clear(key);
                                if (candidates == null) candidates = new ArrayList<>(1);
                                candidates.add(new TransitCandidate(airship, portal));
                                candidateAddedForAirship = true;
                            }
                            // No latch -> never straddled (parked past the portal); nothing to do.
                        }
                        case STRADDLING -> {
                            hostedStraddleLatch.add(key);
                            seenHostedKeys.add(key);
                            // Cross-seam physics: a servoed CLONE BODY in the dest scene
                            // (per-scene model, spec §2.3-2.4), or legacy: dest terrain
                            // baked into the body's scene through the inverse transform.
                            if (IplStraddleCloneBody.isEnabled()) {
                                IplStraddleCloneBody.onStraddleTick(airship, portal, normal);
                            } else {
                                IplStraddleTerrainClone.onStraddleTick(airship, portal, normal);
                            }
                        }
                        case APPROACHING -> {
                            // Ship backed out of a straddle (or hasn't reached the plane):
                            // clear the latch but keep the locked normal while still nearby.
                            if (hostedStraddleLatch.remove(key)) {
                                IplStraddleCloneBody.clear(key, "backed-out");
                                IplStraddleTerrainClone.clear(key);
                            }
                            seenHostedKeys.add(key);
                        }
                    }
                    if (candidateAddedForAirship) break; // one transit per airship per tick
                    continue;
                }

                MirrorRegistry.MirrorEntry entry = MirrorRegistry.get(
                    airship.getUniqueId(), portalUuid
                );

                if (state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED) {
                    // Fully through. Only transit if this pair actually straddled
                    // (mirror exists). If it does, queue the swap and let the
                    // executor despawn the mirror as part of handoff.
                    if (entry != null) {
                        if (candidates == null) candidates = new ArrayList<>(1);
                        candidates.add(new TransitCandidate(airship, portal));
                        candidateAddedForAirship = true;
                        break; // one transit per airship per tick
                    }
                    // No mirror -> never straddled (e.g. parked fully past, or an
                    // approach we never saw begin). Nothing to do.
                    continue;
                }

                if (state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING) {
                    // Leading edge hasn't reached the plane: the mirror must NOT be
                    // visible yet. If one somehow exists for this pair (e.g. the ship
                    // retreated after starting to cross), let the despawn pass below
                    // reap it by simply not marking it seen.
                    continue;
                }

                // STRADDLING: spawn-or-sync the mirror.
                Long lastOp = lastMirrorOpTick.get(key);
                if (lastOp != null && (nowTick - lastOp) < MIRROR_COOLDOWN_TICKS) {
                    // In cooldown. If a mirror exists, keep it alive (mark seen).
                    if (entry != null) {
                        seenMirrors.add(key);
                    }
                    continue;
                }

                try {
                    if (entry == null) {
                        ServerSubLevel spawned = MirrorOps.spawnMirror(airship, portal);
                        if (spawned != null) {
                            seenMirrors.add(key);
                            lastMirrorOpTick.put(key, nowTick);
                        }
                    } else {
                        boolean ok = MirrorOps.syncMirrorPose(airship, portal, entry);
                        if (ok) {
                            seenMirrors.add(key);
                        } else {
                            // Sync failed (mirror gone from dest container, dest dim
                            // unloaded, etc.). Proper despawn -- removes from registry
                            // and emits the wrapped removeSubLevel so any client-side
                            // stale mirror gets cleaned up.
                            MirrorOps.despawnMirror(entry, level.getServer());
                            lastMirrorOpTick.put(key, nowTick);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[IPL-MIRROR] uncaught exception handling mirror for {}/{}",
                        airship.getUniqueId(), portalUuid, t);
                    lastMirrorOpTick.put(key, nowTick);
                }
            }

            // Once we've queued a crossing transit for this airship, skip any
            // further mirror handling for it this tick (the executor will despawn).
            if (candidateAddedForAirship) {
                continue;
            }
        }

        // Reap hosted latch/locked-normal state for pairs no longer near any portal. All
        // hosted ships live in the hosting container, so only its tick does the cleanup
        // (in dim-agnostic mode the legacy mirror path never populates these maps, so the
        // shared lockedCrossingNormal map only holds hosted keys here).
        if (IplDimAgnostic.isEnabled() && IplDimAgnostic.isHostingLevel(level)) {
            hostedStraddleLatch.removeIf(k -> {
                if (!seenHostedKeys.contains(k)) {
                    IplStraddleCloneBody.clear(k, "reaped");
                    IplStraddleTerrainClone.clear(k);
                    return true;
                }
                return false;
            });
            lockedCrossingNormal.keySet().removeIf(k -> !seenHostedKeys.contains(k));
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
                // Contact session ended (ship left the approach zone or retreated):
                // release the locked crossing normal so a fresh re-approach
                // re-snapshots it.
                lockedCrossingNormal.remove(key);
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
                // Hosted transit: parent flip, no mirror bookkeeping.
                if (IplDimAgnostic.isHosted(c.airship)) {
                    lockedCrossingNormal.remove(
                        new MirrorRegistry.MirrorKey(uuid, c.portal.getUUID())
                    );
                    boolean flipped = SableRehomeOps.executeHostedTransit(c.airship, c.portal);
                    if (flipped) {
                        lastTransitTick.put(uuid, nowTick);
                    }
                    continue;
                }

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

                // Crossing session resolved into a transit -- release the locked
                // crossing normal for this pair.
                lockedCrossingNormal.remove(
                    new MirrorRegistry.MirrorKey(uuid, c.portal.getUUID())
                );

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
    /**
     * The source->dest normal to use for {@code airship}'s crossing evaluation
     * against {@code portal}, locked for the duration of the contact session.
     *
     * <p>Orient the portal's raw normal so it points toward where the ship is
     * heading (dest) -- away from the sub-level's current AABB center, since the
     * ship body sits on the source side -- then lock that direction on first
     * contact in {@link #lockedCrossingNormal}. The entry is released when the
     * pair leaves the portal's approach zone (see the cleanup pass).
     */
    private static Vec3 ipl$lockedSourceToDestNormal(ServerSubLevel airship, Portal portal) {
        MirrorRegistry.MirrorKey key =
            new MirrorRegistry.MirrorKey(airship.getUniqueId(), portal.getUUID());
        Vec3 existing = lockedCrossingNormal.get(key);
        if (existing != null) {
            return existing;
        }

        Vec3 center = airship.boundingBox().toMojang().getCenter();
        Vec3 origin = portal.getOriginPos();
        Vec3 rawNormal = portal.getNormal();

        double centerDot =
            (center.x - origin.x) * rawNormal.x +
            (center.y - origin.y) * rawNormal.y +
            (center.z - origin.z) * rawNormal.z;
        // Ship body is on the source side, so the center sits on the negative side
        // of the source->dest normal. If centerDot > 0 the raw normal already
        // points from dest toward the center (i.e. dest->source); flip it.
        Vec3 sourceToDest = centerDot < 0 ? rawNormal : rawNormal.scale(-1.0);

        lockedCrossingNormal.put(key, sourceToDest);
        return sourceToDest;
    }

    private static List<Portal> pickClosestPortalPerDestDim(List<Portal> portals, ServerSubLevel airship) {
        if (portals.size() <= 1) return portals;

        Vec3 airshipCenter = airship.boundingBox().toMojang().getCenter();

        // ResourceKey<Level> is a value type -- safe to use as a map key.
        Map<ResourceKey<Level>, Portal> bestPerDest = new HashMap<>(4);
        Map<ResourceKey<Level>, Double> bestDistSqPerDest = new HashMap<>(4);

        for (Portal portal : portals) {
            ResourceKey<Level> destDim = portal.getDestDim();
            // Rect-clamped distance, NOT origin distance: a dimension-stack global portal's
            // origin sits at (0, seamY, 0), which would lose to any entity portal for ships
            // away from the world axis even when the ship is touching the seam plane.
            double dist = portal.getDistanceToNearestPointInPortal(airshipCenter);
            Double current = bestDistSqPerDest.get(destDim);
            if (current == null || dist < current) {
                bestPerDest.put(destDim, portal);
                bestDistSqPerDest.put(destDim, dist);
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
