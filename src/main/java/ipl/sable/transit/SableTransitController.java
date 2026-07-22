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

    /** Lateral aperture keep-margin for EXISTING sessions (grazing-flap hysteresis). */
    private static final double EXIT_APERTURE_MARGIN = 0.5;

    /**
     * Majority rehome (declarative-straddle phase 3): the transit fires when a live
     * session's crossing fraction exceeds this threshold, instead of waiting for the
     * trailing edge to clear. The declarative recompute derives the mirrored session
     * on the reverse portal next tick (the minority-face rule picks it), so the
     * remaining part keeps physics/render/collision through the handoff — and the
     * majority-native invariant that makes parity geometric is actively maintained.
     * Matches the servo's authority-swap threshold; the swap remains as the fallback
     * when the rehome can't execute. {@code -Dipl.sable.majorityRehome=false} restores
     * rehome-at-fully-CROSSED.
     */
    private static final boolean MAJORITY_REHOME =
        !"false".equals(System.getProperty("ipl.sable.majorityRehome"));
    private static final double REHOME_FRACTION =
        Double.parseDouble(System.getProperty("ipl.sable.rehomeFraction", "0.6"));

    private SableTransitController() {}

    /** Clear hosted transit state when the server stops. */
    public static void onServerStopping(MinecraftServer server) {
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

                UUID portalUuid = portal.getUUID();
                StraddleKey key = new StraddleKey(
                    airship.getUniqueId(), portalUuid
                );
                boolean haveSession = IplStraddleCloneBody.hasSessionKey(key)
                    || IplStraddleTerrainClone.hasSessionKey(key);

                // STAFF FREEZE: while a creative-staff drag session owns this body, a
                // same-dimension portal never transits it. The held construction simply
                // straddles on its image colliders — it can go fully in and be pulled fully
                // back out with its native pose (and rotation) untouched. Transit resumes
                // with normal rules the tick after release. Cross-dimension behavior is
                // deliberately unchanged (its handoff pipeline is verified).
                boolean heldSameDim =
                    portal.getDestDim().equals(portalQueryLevel.dimension())
                        && IplStaffPortalDragState.isHeldByStaff(airship.getUniqueId());

                Vec3 normal = portal.getNormal().scale(-1.0);
                PortalCrossingDetector.CrossingState state = PortalCrossingDetector.evaluate(
                    airship, portal, normal, haveSession ? EXIT_APERTURE_MARGIN : 0.0);

                boolean straddlingAperture =
                    state.phase() == PortalCrossingDetector.CrossingPhase.STRADDLING
                        && state.intersectsPortalAperture();

                if (straddlingAperture) {
                    double fraction = ipl$crossedFraction(airship, portal, normal);

                    // Minority-face parity: the opposite coincident face evaluates
                    // the same geometry with the complementary fraction and claims it.
                    if (!haveSession && fraction > 0.5 + 1.0e-6) {
                        continue;
                    }
                    seenHostedKeys.add(key);

                    // Majority rehome: past the threshold the dest side owns the ship —
                    // flip NOW. The session is NOT cleared here: it dies only when the
                    // transit actually succeeds (see the candidate execution). Clearing
                    // first stranded ships whose transit failed once — past 0.5 the
                    // minority rule refuses to re-open this face and would open the
                    // OPPOSITE face with inverted parity instead, making forward travel
                    // structurally impossible.
                    if (MAJORITY_REHOME && haveSession && fraction > REHOME_FRACTION
                        && !heldSameDim) {
                        if (candidates == null) candidates = new ArrayList<>(1);
                        candidates.add(new TransitCandidate(airship, portal, true));
                        candidateAddedForAirship = true;
                        break;
                    }

                    // Session first, sync second: the one-session-per-ship gate (and
                    // any other spawn decline) must be able to veto BEFORE the client
                    // hears about the session — announced-but-blocked sessions were
                    // unreapable phantoms that left stale clip planes on the ship.
                    if (IplStraddleCloneBody.isEnabled()) {
                        IplStraddleCloneBody.onStraddleTick(airship, portal, normal);
                    } else {
                        IplStraddleTerrainClone.onStraddleTick(airship, portal, normal);
                    }
                    if (!haveSession && (IplStraddleCloneBody.hasSessionKey(key)
                        || IplStraddleTerrainClone.hasSessionKey(key))) {
                        IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                    }
                    continue;
                }

                if (state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED
                    && (haveSession || state.enteredFromSourceAperture())) {
                    // STAFF FREEZE, fully-through half of the rule: a held construction
                    // that fully cleared a same-dimension aperture keeps its session (image
                    // colliders, clip seam, projection) instead of transiting. The clip
                    // plane fully hides the native half and the image is the whole visible
                    // body, so render and collision stay correct while it is parked beyond
                    // the plane or pulled back out. Release lets this branch fire normally.
                    if (heldSameDim) {
                        seenHostedKeys.add(key);
                        if (IplStraddleCloneBody.isEnabled()) {
                            IplStraddleCloneBody.onStraddleTick(airship, portal, normal);
                        } else {
                            IplStraddleTerrainClone.onStraddleTick(airship, portal, normal);
                        }
                        if (!haveSession && (IplStraddleCloneBody.hasSessionKey(key)
                            || IplStraddleTerrainClone.hasSessionKey(key))) {
                            IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                        }
                        continue;
                    }
                    if (haveSession) {
                        IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "crossed");
                    }
                    IplStraddleCloneBody.clear(key, "crossed");
                    IplStraddleTerrainClone.clear(key);
                    if (candidates == null) candidates = new ArrayList<>(1);
                    candidates.add(new TransitCandidate(airship, portal, false));
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
            // Sync-advertised keys too: an advertised session without a physical
            // backing must never outlive its geometry (stale clip planes client-side).
            live.addAll(IplStraddleSessionSync.activeKeys());
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
                    // Rehome candidates keep their session until the transit SUCCEEDS
                    // (a pre-cleared session on a failed transit strands the ship past
                    // 0.5, where the minority rule can only re-open inverted parity).
                    if (c.rehome()) {
                        StraddleKey key = new StraddleKey(uuid, c.portal.getUUID());
                        IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "rehomed");
                        IplStraddleCloneBody.clear(key, "rehomed");
                        IplStraddleTerrainClone.clear(key);
                    }
                    // No exit lock anymore (declarative phase 3): re-transiting the
                    // reverse portal requires a genuine majority back-crossing — the
                    // minority-face rule opens the reverse session at ~1-REHOME_FRACTION
                    // and the ship must travel the full hysteresis band to flip again.
                    IplStaffPortalDragState.onTransitCompleted(c.airship.getUniqueId(), c.portal);
                    // EAGER re-derivation: a majority rehome leaves the minority part
                    // still straddling. Waiting for the next tick's recompute opened a
                    // one-tick hole (no image, no clone, no collision in the old dim —
                    // riders fell through). Deriving the reverse session NOW is the same
                    // declarative rule, just evaluated immediately: the client receives
                    // session-end + handoff + session-start in one packet flush.
                    ipl$deriveReverseSessionNow(c.airship, c.portal);
                } else if (c.rehome()) {
                    LOG.warn("[IPL-TRANSIT] majority rehome transit declined for uuid={} "
                        + "portal={} — session retained, retrying next tick",
                        uuid, c.portal.getUUID());
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
     * Evaluate the declarative session rule for the REVERSE portal immediately after a
     * majority rehome, instead of waiting one tick for the normal recompute. Considers
     * both faces of the reverse pair; the minority-face rule picks the correct one
     * (the freshly-flipped ship sits at ~{@code 1 - REHOME_FRACTION} through it, and
     * the opposite face fails the ≤0.5 test). Idempotent with the next tick's loop.
     */
    private static void ipl$deriveReverseSessionNow(ServerSubLevel airship, Portal entrance) {
        ServerLevel newParent = IplDimAgnostic.getServerParentLevel(airship);
        if (newParent == null) return;

        List<Portal> faces = new ArrayList<>(2);
        Portal reverse = qouteall.imm_ptl.core.portal.PortalExtension.get(entrance).reversePortal;
        if (reverse != null) faces.add(reverse);
        if (reverse != null) {
            Portal twin = qouteall.imm_ptl.core.portal.PortalExtension.get(reverse).flippedPortal;
            if (twin != null) faces.add(twin);
        }

        for (Portal face : faces) {
            if (face.isRemoved() || !face.isTeleportable()) continue;
            if (!ipl$isCanonicalEntranceFace(face, faces)) continue;

            Vec3 normal = face.getNormal().scale(-1.0);
            PortalCrossingDetector.CrossingState state =
                PortalCrossingDetector.evaluate(airship, face, normal);
            if (state.phase() != PortalCrossingDetector.CrossingPhase.STRADDLING
                || !state.intersectsPortalAperture()) {
                continue;
            }
            if (ipl$crossedFraction(airship, face, normal) > 0.5 + 1.0e-6) continue;

            // Session first, sync second: the sync must never announce a session
            // whose spawn was declined (e.g. the body's scene migration lands a tick
            // later) — the next tick's recompute then derives it normally.
            if (IplStraddleCloneBody.isEnabled()) {
                IplStraddleCloneBody.onStraddleTick(airship, face, normal);
            } else {
                IplStraddleTerrainClone.onStraddleTick(airship, face, normal);
            }
            StraddleKey key = new StraddleKey(airship.getUniqueId(), face.getUUID());
            if (IplStraddleCloneBody.hasSessionKey(key)
                || IplStraddleTerrainClone.hasSessionKey(key)) {
                IplStraddleSessionSync.onSessionStart(newParent.getServer(), airship, face);
                LOG.info("[IPL-TRANSIT] eager reverse session uuid={} portal={}",
                    airship.getUniqueId(), face.getUUID());
            } else {
                LOG.info("[IPL-TRANSIT] eager reverse session deferred for uuid={} "
                    + "(spawn declined; next tick derives it)", airship.getUniqueId());
            }
            return;
        }
    }

    private record TransitCandidate(ServerSubLevel airship, Portal portal, boolean rehome) {}
}
