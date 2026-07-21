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

    /** Output aperture faces that cannot be re-entered before an exit completes. */
    private static final Map<UUID, java.util.Set<Portal>> pendingExitPortalsByAirship = new HashMap<>();

    private SableTransitController() {}

    /** Clear hosted transit state when the server stops. */
    public static void onServerStopping(MinecraftServer server) {
        pendingExitPortalsByAirship.clear();
        IplStraddleSessionSync.clearAll();
        IplPortalRimManager.clearAll();
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
        // Always-on portal containment rims track portal entity lifecycle per level.
        IplPortalRimManager.tick(level);
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

            // DECLARATIVE session derivation (phase 2 of the declarative-straddle
            // rework): a session exists iff the ship's OBB currently STRADDLES a
            // portal's plane THROUGH its aperture — no entry events, no latches.
            // Existing sessions are kept with a lateral aperture margin (hysteresis
            // against grazing flap); new sessions additionally require the minority-
            // face parity rule: of a pair of coincident opposite faces, only the one
            // whose crossing direction puts LESS than half the ship "through" may
            // open a session — with the majority-native invariant, the through part
            // is always the minority, which makes parity geometric, not historical.
            // Transit fires when a LIVE session reaches CROSSED (the session is the
            // straddle memory), or on a genuine single-tick aperture crossing.
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
                boolean haveSession = IplStraddleCloneBody.hasSessionKey(key)
                    || IplStraddleTerrainClone.hasSessionKey(key);

                Vec3 normal = portal.getNormal().scale(-1.0);
                PortalCrossingDetector.CrossingState state = PortalCrossingDetector.evaluate(
                    airship, portal, normal, haveSession ? EXIT_APERTURE_MARGIN : 0.0);

                boolean straddlingAperture =
                    state.phase() == PortalCrossingDetector.CrossingPhase.STRADDLING
                        && state.intersectsPortalAperture();

                if (straddlingAperture && !haveSession) {
                    // Minority-face parity: the opposite coincident face evaluates the
                    // same geometry with the complementary fraction and claims it.
                    if (ipl$crossedFraction(airship, portal, normal) > 0.5 + 1.0e-6) {
                        continue;
                    }
                    IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                    haveSession = true;
                }

                if (straddlingAperture) {
                    seenHostedKeys.add(key);
                    if (IplStraddleCloneBody.isEnabled()) {
                        IplStraddleCloneBody.onStraddleTick(airship, portal, normal);
                    } else {
                        IplStraddleTerrainClone.onStraddleTick(airship, portal, normal);
                    }
                    continue;
                }

                if (state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED
                    && (haveSession || state.enteredFromSourceAperture())) {
                    if (haveSession) {
                        IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "crossed");
                    }
                    IplStraddleCloneBody.clear(key, "crossed");
                    IplStraddleTerrainClone.clear(key);
                    if (candidates == null) candidates = new ArrayList<>(1);
                    candidates.add(new TransitCandidate(airship, portal));
                    candidateAddedForAirship = true;
                } else {
                    String reason = state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING
                        ? "backed-out" : "left-aperture";
                    if (haveSession) {
                        IplStraddleCloneBody.clear(key, reason);
                        IplStraddleTerrainClone.clear(key);
                    }
                    // Unconditional + idempotent: a synced session whose spawn kept
                    // failing has no local session key but must still be retracted.
                    IplStraddleSessionSync.onSessionEnd(level.getServer(), key, reason);
                }
                if (candidateAddedForAirship) break;
            }

            if (candidateAddedForAirship) {
                continue;
            }
        }

        // Reap sessions whose (ship, portal) pair wasn't derivable this tick at all —
        // portal unloaded/removed, or the ship left every portal's neighborhood. All
        // hosted ships live in the hosting container, so only its tick sweeps.
        if (IplDimAgnostic.isHostingLevel(level)) {
            java.util.Set<StraddleKey> live = new java.util.HashSet<>(
                IplStraddleCloneBody.sessionKeys());
            live.addAll(IplStraddleTerrainClone.sessionKeys());
            for (StraddleKey k : live) {
                if (!seenHostedKeys.contains(k)) {
                    IplStraddleCloneBody.clear(k, "reaped");
                    IplStraddleTerrainClone.clear(k);
                    IplStraddleSessionSync.onSessionEnd(level.getServer(), k, "reaped");
                }
            }
        }

        if (candidates == null) return;

        for (TransitCandidate c : candidates) {
            UUID uuid = c.airship.getUniqueId();
            try {
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
     * Fraction of the ship's AABB extent past the portal plane along
     * {@code sourceToDest} — the minority-face parity test for NEW sessions: of two
     * coincident opposite faces, only the one seeing less than half the ship
     * "through" may open a session, so the majority side is always the native one.
     * (Per-session normal locking is gone: the crossing direction is a constant of
     * the portal FACE, {@code -getNormal()}, and face selection itself is what this
     * rule pins down.)
     */
    private static double ipl$crossedFraction(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDest
    ) {
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box = airship.boundingBox();
        Vec3 p = portal.getOriginPos();
        double tc = ((box.minX() + box.maxX()) * 0.5 - p.x) * sourceToDest.x
                  + ((box.minY() + box.maxY()) * 0.5 - p.y) * sourceToDest.y
                  + ((box.minZ() + box.maxZ()) * 0.5 - p.z) * sourceToDest.z;
        double r = (box.maxX() - box.minX()) * 0.5 * Math.abs(sourceToDest.x)
                 + (box.maxY() - box.minY()) * 0.5 * Math.abs(sourceToDest.y)
                 + (box.maxZ() - box.minZ()) * 0.5 * Math.abs(sourceToDest.z);
        double tMin = tc - r, tMax = tc + r;
        if (tMax <= 0.0) return 0.0;
        if (tMin >= 0.0 || tMax - tMin < 1e-9) return 1.0;
        return tMax / (tMax - tMin);
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
