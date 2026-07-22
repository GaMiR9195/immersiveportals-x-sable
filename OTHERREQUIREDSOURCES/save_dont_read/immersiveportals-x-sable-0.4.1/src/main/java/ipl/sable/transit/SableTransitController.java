package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    /** Lateral-only output-aperture allowance for a construction's final edge. */
    private static final double EXIT_APERTURE_MARGIN = 0.5;

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

    /** Output aperture faces that cannot be re-entered before an exit completes. */
    private static final Map<UUID, java.util.Set<Portal>> pendingExitPortalsByAirship = new HashMap<>();

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
        lockedCrossingNormal.clear();
        hostedStraddleLatch.clear();
        pendingExitPortalsByAirship.clear();
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

            // Collapse only true duplicate entrance faces, never all portals that share a
            // destination dimension. Opposite faces may occupy the exact same plane and
            // must remain independent; UUID order makes equivalent companions stable.
            nearby.sort(Comparator.comparing(portal -> portal.getUUID().toString()));

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
            for (Portal portal : nearby) {
                if (!ipl$isCanonicalEntranceFace(portal, nearby)) continue;
                if (hosted && ipl$isBlockedOutputPortal(airship, portal)) {
                    continue;
                }

                UUID portalUuid = portal.getUUID();
                MirrorRegistry.MirrorKey key = new MirrorRegistry.MirrorKey(
                    airship.getUniqueId(), portalUuid
                );

                Vec3 normal = ipl$lockedSourceToDestNormal(airship, portal);
                PortalCrossingDetector.CrossingState state =
                    PortalCrossingDetector.evaluate(airship, portal, normal);

                // A portal is a finite aperture, not its infinite supporting plane.
                // Without this gate, brushing the plane several blocks beside the
                // frame starts a hosted latch; a later fast move to the other side
                // then incorrectly flips the construction into the destination.
                if (state.phase() == PortalCrossingDetector.CrossingPhase.STRADDLING
                    && !state.intersectsPortalAperture()) {
                    if (hostedStraddleLatch.remove(key)) {
                        IplStraddleCloneBody.clear(key, "left-aperture");
                        IplStraddleTerrainClone.clear(key);
                    }
                    continue;
                }

                // Starting a session requires an actual source-side aperture crossing
                // during this physics step. A body already straddling nearby planes
                // cannot claim several nearly-coplanar portal faces at once.
                boolean canStartContact = state.enteredFromSourceAperture();

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
                        nearby.size(),
                        hostedStraddleLatch.contains(key));
                }

                // Hosted (dim-agnostic) dispatch: no mirrors. The explicit straddle latch
                // replaces "mirror exists" as the only-swap-after-straddle guard; CROSSED
                // resolves to a parent flip via SableRehomeOps.executeHostedTransit.
                if (hosted) {
                    switch (state.phase()) {
                        case CROSSED -> {
                            if (hostedStraddleLatch.remove(key) || state.enteredFromSourceAperture()) {
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
                            if (!hostedStraddleLatch.contains(key) && !canStartContact) {
                                continue;
                            }
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
                    if (entry != null || state.enteredFromSourceAperture()) {
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
                if (entry == null && !canStartContact) {
                    continue;
                }
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
                        ipl$beginExitLock(c.airship, c.portal);
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

                SableTransitOps.executeTransit(c.airship, c.portal);
            } catch (Throwable t) {
                LOG.error("[IPL-TRANSIT] uncaught exception executing transit for uuid={}",
                    uuid, t);
            }
        }
    }

    /**
     * The source->dest normal to use for {@code airship}'s crossing evaluation
     * against {@code portal}, locked for the duration of the contact session.
     *
     * <p>IP's portal normal points to the source/remaining side. Its opposite is
     * therefore the fixed source-to-destination direction. Never infer it from
     * the ship center: a straddling body has points on both sides, and coplanar
     * flipped portal faces would otherwise steal one another's contact sessions.
     */
    private static Vec3 ipl$lockedSourceToDestNormal(ServerSubLevel airship, Portal portal) {
        MirrorRegistry.MirrorKey key =
            new MirrorRegistry.MirrorKey(airship.getUniqueId(), portal.getUUID());
        Vec3 existing = lockedCrossingNormal.get(key);
        if (existing != null) {
            return existing;
        }

        Vec3 sourceToDest = portal.getNormal().scale(-1.0);

        lockedCrossingNormal.put(key, sourceToDest);
        return sourceToDest;
    }

    /**
     * IP can create companion entities for one physical doorway. They have the same
     * directed source rectangle and the same mapped origin; retain one deterministic
     * representative. Opposite normals deliberately fail this test, so a two-sided
     * portal at zero separation still has one independent candidate per face.
     */
    private static boolean ipl$isCanonicalEntranceFace(Portal portal, List<Portal> candidates) {
        for (Portal other : candidates) {
            if (other == portal || !portal.getDestDim().equals(other.getDestDim())) continue;
            if (portal.getOriginPos().distanceToSqr(other.getOriginPos()) > 1.0e-12
                || portal.getDestPos().distanceToSqr(other.getDestPos()) > 1.0e-12
                || Math.abs(portal.getWidth() - other.getWidth()) > 1.0e-6
                || Math.abs(portal.getHeight() - other.getHeight()) > 1.0e-6
                || portal.getNormal().dot(other.getNormal()) < 0.999999) {
                continue;
            }
            if (portal.getUUID().toString().compareTo(other.getUUID().toString()) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Suppress only reverse-portal re-entry while its finite aperture still cuts
     * the OBB. A different portal may be entered normally during that time.
     */
    private static boolean ipl$isBlockedOutputPortal(ServerSubLevel airship, Portal candidate) {
        java.util.Set<Portal> outputs = pendingExitPortalsByAirship.get(airship.getUniqueId());
        if (outputs == null || !outputs.contains(candidate)) return false;

        PortalCrossingDetector.CrossingState state = PortalCrossingDetector.evaluate(
            airship, candidate, candidate.getNormal(), EXIT_APERTURE_MARGIN
        );
        if ((state.phase() == PortalCrossingDetector.CrossingPhase.STRADDLING
            && state.intersectsPortalAperture())) {
            return true;
        }

        pendingExitPortalsByAirship.remove(airship.getUniqueId());
        return false;
    }

    private static void ipl$beginExitLock(ServerSubLevel airship, Portal entrance) {
        Portal output = PortalExtension.get(entrance).reversePortal;
        if (output == null) return;

        java.util.Set<Portal> outputFaces = new java.util.HashSet<>();
        outputFaces.add(output);
        Portal flipped = PortalExtension.get(output).flippedPortal;
        if (flipped != null) {
            outputFaces.add(flipped);
        }
        pendingExitPortalsByAirship.put(airship.getUniqueId(), outputFaces);
    }

    private record TransitCandidate(ServerSubLevel airship, Portal portal) {}
}
