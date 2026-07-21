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
     * Per-(source, portal) locked source->dest normal for the crossing-phase
     * evaluation, held for the duration of a contact session. This is the ONLY
     * holder of crossing parity: clients mirror it via
     * {@link IplStraddleSessionSync} instead of re-deriving it from geometry.
     *
     * <p>Locking matters because a free-rotating airship's AABB center can wobble
     * across the plane mid-straddle; without the lock the oriented normal could
     * flip and bounce the crossing phase. The entry is dropped when the pair is no
     * longer near the portal (see the cleanup pass). Server-thread only, so a
     * plain HashMap is fine.
     */
    private static final Map<StraddleKey, Vec3> lockedCrossingNormal = new HashMap<>();

    /**
     * A (subLevel, portal) key enters on STRADDLING and leaves when the ship backs out or
     * completes the parent flip. It prevents a body parked beyond a portal from transiting.
     */
    private static final java.util.Set<StraddleKey> hostedStraddleLatch = new java.util.HashSet<>();

    /** Output aperture faces that cannot be re-entered before an exit completes. */
    private static final Map<UUID, java.util.Set<Portal>> pendingExitPortalsByAirship = new HashMap<>();

    private SableTransitController() {}

    /** Clear hosted transit state when the server stops. */
    public static void onServerStopping(MinecraftServer server) {
        lockedCrossingNormal.clear();
        hostedStraddleLatch.clear();
        pendingExitPortalsByAirship.clear();
        IplStraddleSessionSync.clearAll();
        IplStraddleCloneBody.clearAll();
        IplStraddleTerrainClone.clearAll();
        SableRehomeOps.resetBootRestore();
        ipl.sable.dim.IplSceneOwnership.clearAll();
    }

    /**
     * Called once per server tick per dimension's container, at the TAIL of
     * {@code ServerSubLevelContainer.tick}.
     */
    public static void onContainerTick(ServerSubLevelContainer container) {
        ServerLevel level = (ServerLevel) container.getLevel();
        if (level == null) return;
        // Per-scene model: migrate any hosted body whose scene doesn't match its parent —
        // covers boot-restored ships whose parent resolved after the body was created
        // (fallback landed it in the hosting scene) and any missed flip path.
        if (ipl.sable.dim.IplSceneOwnership.isEnabled()
            && ipl.sable.dim.IplDimAgnostic.isHostingLevel(level)) {
            ipl.sable.dim.IplSceneOwnership.reconcile(container);
        }

        java.util.Set<StraddleKey> seenHostedKeys = new java.util.HashSet<>();

        // Collect transit candidates first so we don't mutate iterators while iterating
        // (transit will call container.removeSubLevel which mutates allSubLevels).
        List<TransitCandidate> candidates = null;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel airship)) continue;
            if (airship.isRemoved()) continue;

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

            boolean hosted = IplDimAgnostic.isHosted(airship);
            if (!hosted) {
                continue;
            }

            ServerLevel portalQueryLevel = IplDimAgnostic.getServerParentLevel(airship);
            if (portalQueryLevel == null) continue;

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
                StraddleKey key = new StraddleKey(
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
                        IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "left-aperture");
                    }
                    continue;
                }

                // Starting a session requires an actual source-side aperture crossing
                // during this physics step. A body already straddling nearby planes
                // cannot claim several nearly-coplanar portal faces at once.
                boolean canStartContact = state.enteredFromSourceAperture();

                switch (state.phase()) {
                    case CROSSED -> {
                        boolean hadLatch = hostedStraddleLatch.remove(key);
                        if (hadLatch) {
                            IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "crossed");
                        }
                        if (hadLatch || state.enteredFromSourceAperture()) {
                            IplStraddleCloneBody.clear(key, "crossed");
                            IplStraddleTerrainClone.clear(key);
                            if (candidates == null) candidates = new ArrayList<>(1);
                            candidates.add(new TransitCandidate(airship, portal));
                            candidateAddedForAirship = true;
                        }
                    }
                    case STRADDLING -> {
                        if (!hostedStraddleLatch.contains(key) && !canStartContact) continue;
                        if (hostedStraddleLatch.add(key)) {
                            IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                        }
                        seenHostedKeys.add(key);
                        if (IplStraddleCloneBody.isEnabled()) {
                            IplStraddleCloneBody.onStraddleTick(airship, portal, normal);
                        } else {
                            IplStraddleTerrainClone.onStraddleTick(airship, portal, normal);
                        }
                    }
                    case APPROACHING -> {
                        if (hostedStraddleLatch.remove(key)) {
                            IplStraddleCloneBody.clear(key, "backed-out");
                            IplStraddleTerrainClone.clear(key);
                            IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "backed-out");
                        }
                        seenHostedKeys.add(key);
                    }
                }
                if (candidateAddedForAirship) break;
            }

            if (candidateAddedForAirship) {
                continue;
            }
        }

        // Reap hosted latch/locked-normal state for pairs no longer near any portal. All
        // hosted ships live in the hosting container, so only its tick does the cleanup.
        if (IplDimAgnostic.isHostingLevel(level)) {
            hostedStraddleLatch.removeIf(k -> {
                if (!seenHostedKeys.contains(k)) {
                    IplStraddleCloneBody.clear(k, "reaped");
                    IplStraddleTerrainClone.clear(k);
                    IplStraddleSessionSync.onSessionEnd(level.getServer(), k, "reaped");
                    return true;
                }
                return false;
            });
            lockedCrossingNormal.keySet().removeIf(k -> !seenHostedKeys.contains(k));
        }

        if (candidates == null) return;

        for (TransitCandidate c : candidates) {
            UUID uuid = c.airship.getUniqueId();
            try {
                lockedCrossingNormal.remove(
                    new StraddleKey(uuid, c.portal.getUUID())
                );
                boolean flipped = SableRehomeOps.executeHostedTransit(c.airship, c.portal);
                if (flipped) {
                    ipl$beginExitLock(c.airship, c.portal);
                    IplStaffPortalDragState.onTransitCompleted(c.airship.getUniqueId(), c.portal);
                }
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
        StraddleKey key = new StraddleKey(airship.getUniqueId(), portal.getUUID());
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
