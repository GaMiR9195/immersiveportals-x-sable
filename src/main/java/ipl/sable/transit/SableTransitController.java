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
    /** Actor identity only changes at lifecycle seams, not on every physics tick. */
    private static final int ACTOR_RESYNC_INTERVAL = 100;
    private static long hostedTransitTick;

    private SableTransitController() {}

    /** Clear hosted transit state when the server stops. */
    public static void onServerStopping(MinecraftServer server) {
        // Post-drain diagnostic (this runs at stopServer RETURN): any chunk still
        // loaded in the hosting dim here survived the shutdown drain — the slow-
        // shutdown / unload-flap investigation reads this line.
        try {
            ServerLevel hosting =
                ipl.sable.dim.SableSubLevelDimension.getSableSubLevelsOrNull(server);
            if (hosting != null) {
                var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer
                    .getContainer((net.minecraft.world.level.Level) hosting);
                LOG.info("[IPL-SHUTDOWN] hosting dim post-drain: loadedChunks={} liveSubLevels={}",
                    hosting.getChunkSource().getLoadedChunksCount(),
                    container == null ? -1 : container.getAllSubLevels().size());
            }
        } catch (Throwable t) {
            LOG.warn("[IPL-SHUTDOWN] post-drain diagnostic failed", t);
        }
        IplGrabChain.clearAll();
        IplStraddleSessionSync.clearAll();
        IplPortalRimManager.clearAll();
        IplAtlasStraddleSession.clearAll();
        IplPortalVolumeCache.clear();
        PortalCrossingDetector.clearTrails();
        IplShipPortalAnchor.clearAll();
        IplShipNetherPortal.clearPending();
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
        level.getProfiler().push("ipl_sable_transit");
        if (IplDimAgnostic.isHostingLevel(level)) {
            IplEnteringVolumeVisualization.beginTick();
            PortalCrossingDetector.beginTrailTick();
            IplPortalVolumeCache.beginTick();
        }
        // Always-on portal containment rims track portal entity lifecycle per level.
        IplPortalRimManager.tick(level);
        // Atlas keeps real bodies in the hosting chart and reconciles parent-chart images.
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

            boolean hosted = IplDimAgnostic.isHosted(airship);
            if (!hosted) {
                continue;
            }
            if (hostedTransitTick % ACTOR_RESYNC_INTERVAL == 0) {
                int rebound = SableTransitOps.resyncStaleActors(airship);
                if (rebound > 0) {
                    LOG.info("[IPL-TRANSIT] rebound {} stale physics actor(s) to live block "
                        + "entities for uuid={}", rebound, airship.getUniqueId());
                }
            }
            IplPortalVolumeCache.touch(airship);

            ServerLevel portalQueryLevel = IplDimAgnostic.getServerParentLevel(airship);
            if (portalQueryLevel == null) continue;
            IplEnteringVolumeVisualization.captureTick(airship, portalQueryLevel);
            PortalCrossingDetector.captureTrail(airship);

            // Convert Sable's BoundingBox3d to MC AABB for the portal query.
            // Staff's PD motor can cross a complete portal in one tick. Query the swept OBB,
            // not only the final pose, or the source aperture is already out of range and the
            // detector never sees its own fast-crossing path.
            AABB airshipAabb = PortalCrossingDetector.sweptBounds(airship)
                .inflate(PORTAL_QUERY_INFLATION);
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

            // A session begins only from the continuous occupied-cube sweep. Its B cap is
            // part of that same sweep, so first contact at B starts a session even when the
            // earlier trail never met the plane. A body fully beyond B transits immediately
            // if its trail crossed the aperture; there is no endpoint fallback detector.
            boolean candidateAddedForAirship = false;
            for (Portal portal : nearby) {
                if (!ipl$isCanonicalEntranceFace(portal, nearby)) continue;

                UUID portalUuid = portal.getUUID();
                // A carrier ignores its OWN anchored portal completely: no session,
                // no crossing state, no transit (IplShipPortalAnchor).
                if (IplShipPortalAnchor.isAnchorShip(portal, airship.getUniqueId())) {
                    continue;
                }
                StraddleKey key = new StraddleKey(
                    airship.getUniqueId(), portalUuid
                );
                boolean haveSession = IplAtlasStraddleSession.hasSessionKey(key);

                // Staff-held bodies ride the SAME declarative pipeline as free ones. The
                // old staff freeze (no same-dim transit while held, plus a held-portal pin
                // blocking every other face) made a fully-inserted body permanently unable
                // to enter any OTHER portal for the rest of the grab and made sequential
                // multi-portal drags structurally impossible. Transit while held is safe:
                // the parent flip is an exact isometry, and the grab chain rebases the
                // goal and held orientation through the same portal in the same tick, so
                // the constraint error is invariant across the flip (no yank).
                Vec3 normal = portal.getNormal().scale(-1.0);
                PortalCrossingDetector.CrossingState state = PortalCrossingDetector.evaluate(
                    airship, portal, normal, haveSession ? EXIT_APERTURE_MARGIN : 0.0);

                boolean straddlingAperture = state.phase()
                    == PortalCrossingDetector.CrossingPhase.STRADDLING
                    && state.currentIntersectsPortalAperture()
                    // A live session is pinned to this face. A new one additionally
                    // needs continuous source-to-destination sweep evidence.
                    && (haveSession || state.sweptIntersectsPortalAperture());

                if (straddlingAperture) {
                    Portal twin = ipl$oppositeCoincidentFace(portal, nearby);

                    // The aperture has two faces. New sessions belong only to the face
                    // crossed by the real swept block volume; an existing session keeps
                    // its face while the ship backs out through the same aperture.
                    if (!ipl$ownsCoincidentFace(portal, twin, state, haveSession)) {
                        continue;
                    }

                    // ONE SESSION PER COINCIDENT-FACE PAIR (dual-parity exclusion).
                    // A live session on the twin owns the doorway's parity; this face
                    // may not open. Scoped to coincident opposite faces only —
                    // multi-straddle sessions on DIFFERENT doorways are unaffected.
                    boolean twinHasSession = twin != null
                        && ipl$hasAnySession(airship.getUniqueId(), twin);
                    if (!haveSession && twinHasSession) {
                        continue;
                    }
                    if (haveSession && twinHasSession) {
                        // Defensive self-heal. Runtime sessions cannot normally persist
                        // a dual state; a deterministic UUID order chooses stale loser.
                        LOG.warn("[IPL-TRANSIT] dual-parity sessions on coincident "
                            + "faces for uuid={} — clearing duplicate face {}",
                            airship.getUniqueId(), portalUuid);
                        IplAtlasStraddleSession.clear(key, "dual-parity-heal");
                        IplStraddleSessionSync.onSessionEnd(
                            level.getServer(), key, "dual-parity-heal");
                        continue;
                    }
                    seenHostedKeys.add(key);
                    IplEnteringVolumeVisualization.clearBuffer(airship);

                    // Session first, sync second: the one-session-per-ship gate (and
                    // any other spawn decline) must be able to veto BEFORE the client
                    // hears about the session — announced-but-blocked sessions were
                    // unreapable phantoms that left stale clip planes on the ship.
                    IplAtlasStraddleSession.onStraddleTick(airship, portal, normal);
                    if (!haveSession && IplAtlasStraddleSession.hasSessionKey(key)) {
                        IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                    }
                    continue;
                }

                boolean completedOwnedCrossing = haveSession
                    ? ipl$continuesThroughOwnedFace(state, true)
                    : state.startedBeforePortalPlane()
                        && state.sweptIntersectsPortalAperture()
                        && ipl$continuesThroughOwnedFace(state, false);
                if (state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED
                    && completedOwnedCrossing) {
                    // A live session is proof that this exact face admitted the source
                    // volume. Once every block has left through its destination side,
                    // rehome must not depend on the short diagnostic trail still touching
                    // the aperture: slow, long ships can finish after that trail expired.
                    // Without a session (one-tick crossing), retain the full finite sweep
                    // evidence before allowing the handoff.
                    Portal twin = ipl$oppositeCoincidentFace(portal, nearby);
                    if (!haveSession && twin != null
                        && ipl$hasAnySession(airship.getUniqueId(), twin)) {
                        // Backing out can leave this opposite face fully crossed. The
                        // already-live session owns the doorway until it reaches source.
                        continue;
                    }
                    if (!ipl$ownsCoincidentFace(portal, twin, state, haveSession)) {
                        continue;
                    }
                    // Candidate execution happens after the declarative reap. Keep the
                    // image/clip seam alive through the handoff, then retire it only after
                    // the mapped body and client timeline have switched frames.
                    if (haveSession) seenHostedKeys.add(key);
                    if (candidates == null) candidates = new ArrayList<>(1);
                    candidates.add(new TransitCandidate(
                        airship, portal, haveSession, ipl$rigidGroupMates(airship)));
                    candidateAddedForAirship = true;
                } else if (haveSession
                    && state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED
                    && state.currentSweepDirection() == PortalCrossingDetector.SweepDirection.TOWARD_SOURCE) {
                    // A pulled-back body can remain wholly destination-side for one tick.
                    // Keep its original face pinned until it crosses the source exit band.
                    seenHostedKeys.add(key);
                    IplAtlasStraddleSession.onStraddleTick(airship, portal, normal);
                    continue;
                } else {
                    String reason = state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING
                        ? "backed-out" : "left-aperture";
                    if (haveSession) {
                        IplAtlasStraddleSession.clear(key, reason);
                        // The source-frame debug buffer is replaced in the same tick as
                        // a complete exit, rather than waiting for next tick's capture.
                        IplEnteringVolumeVisualization.replaceBuffer(airship);
                        PortalCrossingDetector.resetTrail(airship);
                        // Do not evaluate the opposite coincident face using the unwound
                        // segment. Fresh trail starts next tick from this real source pose.
                        break;
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
                IplAtlasStraddleSession.sessionKeys());
            // Sync-advertised keys too: an advertised session without a physical
            // backing must never outlive its geometry (stale clip planes client-side).
            live.addAll(IplStraddleSessionSync.activeKeys());
            for (StraddleKey k : live) {
                if (!seenHostedKeys.contains(k)) {
                    IplAtlasStraddleSession.clear(k, "reaped");
                    IplStraddleSessionSync.onSessionEnd(level.getServer(), k, "reaped");
                }
            }
            PortalCrossingDetector.pruneTrails();
            IplPortalVolumeCache.prune();
            hostedTransitTick++;
        }

        if (candidates == null) {
            if (IplDimAgnostic.isHostingLevel(level)) {
                IplEnteringVolumeVisualization.flush(level.getServer());
            }
            level.getProfiler().pop();
            return;
        }

        java.util.Set<UUID> flippedThisTick = new java.util.HashSet<>();
        for (TransitCandidate c : candidates) {
            UUID uuid = c.airship.getUniqueId();
            // Already carried through as another candidate's rigid mate this tick.
            if (flippedThisTick.contains(uuid)) continue;
            try {
                boolean flipped = SableRehomeOps.executeHostedTransit(c.airship, c.portal);
                if (flipped) {
                    flippedThisTick.add(uuid);
                    if (c.hadSession()) {
                        StraddleKey key = new StraddleKey(uuid, c.portal.getUUID());
                        IplAtlasStraddleSession.clear(key, "crossed");
                    }
                    // An immediate fast crossing may never have spent a whole tick in a
                    // straddle session. Its source-frame debug buffer is still invalid
                    // after the parent flip and must not linger behind the portal.
                    IplEnteringVolumeVisualization.clearBuffer(c.airship);
                    PortalCrossingDetector.forgetTrail(c.airship);
                    // Grab chains of every player holding this body rebase through the
                    // exact crossing portal (goal, orientation, beam — one event).
                    IplGrabChain.onBodyTransit(level.getServer(), c.airship.getUniqueId(), c.portal);
                    // Ropes span the portal instead of gating it: re-seam this ship's
                    // rope ends through the transit isometry (pull-through behavior).
                    IplRopePortalSeam.onShipTransit(c.airship, c.portal);
                    // Rigid mates (swivel bearings, couplings) cross ATOMICALLY with
                    // this body — a rigid assembly is one construction, and any split
                    // leaves a joint spanning raw source/dest coordinates.
                    for (ServerSubLevel mate : c.mates()) {
                        UUID mateId = mate.getUniqueId();
                        if (mate.isRemoved() || !flippedThisTick.add(mateId)) continue;
                        boolean mateFlipped = SableRehomeOps.executeHostedTransit(mate, c.portal);
                        if (!mateFlipped) {
                            flippedThisTick.remove(mateId);
                            LOG.warn("[IPL-TRANSIT] rigid mate {} declined group transit "
                                + "through {} — will re-derive next tick",
                                mateId, c.portal.getUUID());
                            continue;
                        }
                        StraddleKey mateKey = new StraddleKey(mateId, c.portal.getUUID());
                        IplAtlasStraddleSession.clear(mateKey, "rehomed-group");
                        PortalCrossingDetector.forgetTrail(mate);
                        IplStraddleSessionSync.onSessionEnd(
                            level.getServer(), mateKey, "rehomed-group");
                        IplGrabChain.onBodyTransit(level.getServer(), mateId, c.portal);
                        IplRopePortalSeam.onShipTransit(mate, c.portal);
                    }
                }
            } catch (Throwable t) {
                LOG.error("[IPL-TRANSIT] uncaught exception executing transit for uuid={}",
                    uuid, t);
            }
        }
        if (IplDimAgnostic.isHostingLevel(level)) {
            IplEnteringVolumeVisualization.flush(level.getServer());
        }
        level.getProfiler().pop();
    }

    /**
     * The rigid assembly containing {@code body}: every other ship reachable through
     * ship-to-ship joints only (swivel bearings, couplings). Rope chains do NOT bridge —
     * roped ships transit independently and the rope spans the portal.
     *
     * <p>These mates cross the portal ATOMICALLY with the evaluated body in the same
     * candidate execution. The previous per-member readiness gate deadlocked: it
     * required every member's live session, but the first member to flip cleared its
     * own session and never straddled the portal again, blocking the rest forever —
     * the "stuck on the wrong logical side" state.
     */
    private static java.util.List<ServerSubLevel> ipl$rigidGroupMates(ServerSubLevel body) {
        if (!ipl.sable.natives.IplRapierNatives.isAvailable()) return java.util.List.of();
        try {
            long scene = ipl.sable.dim.IplSceneOwnership
                .liveSceneHandle((ServerLevel) body.getLevel());
            if (scene == 0) return java.util.List.of();
            int[] ids = ipl.sable.natives.IplRapierNatives.connectedSableBodyIds(
                scene, dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(body), true);
            if (ids.length <= 1) return java.util.List.of();

            var hosting = dev.ryanhcode.sable.api.sublevel.SubLevelContainer
                .getContainer(body.getLevel());
            if (hosting == null) return java.util.List.of();
            int selfId = body.getRuntimeId();
            java.util.List<ServerSubLevel> mates = new java.util.ArrayList<>(ids.length - 1);
            for (int id : ids) {
                if (id == selfId) continue;
                for (var sub : hosting.getAllSubLevels()) {
                    if (sub.getRuntimeId() == id && !sub.isRemoved()) {
                        mates.add(sub);
                        break;
                    }
                }
            }
            return mates;
        } catch (Throwable t) {
            LOG.warn("[IPL-TRANSIT] rigid-group walk failed for uuid={}",
                body.getUniqueId(), t);
            return java.util.List.of();
        }
    }


    /**
     * The coincident OPPOSITE face of {@code portal} (the other side of the same
     * physical doorway), or null. The cluster link is authoritative when present;
     * the geometric fallback covers independent back-to-back portals with no
     * extension binding (same plane, opposed normals, same extents).
     */
    private static Portal ipl$oppositeCoincidentFace(Portal portal, List<Portal> candidates) {
        qouteall.imm_ptl.core.portal.PortalExtension extension =
            qouteall.imm_ptl.core.portal.PortalExtension.get(portal);
        Portal flipped = extension.flippedPortal;
        if (flipped == null && extension.flippedPortalId != null
            && portal.level() instanceof ServerLevel level
            && level.getEntity(extension.flippedPortalId) instanceof Portal byId) {
            flipped = byId;
        }
        if (flipped != null && !flipped.isRemoved()) return flipped;
        for (Portal other : candidates) {
            if (other == portal) continue;
            if (portal.getOriginPos().distanceToSqr(other.getOriginPos()) > 1.0e-12) continue;
            if (portal.getNormal().dot(other.getNormal()) > -0.999999) continue;
            if (Math.abs(portal.getWidth() - other.getWidth()) > 1.0e-6
                || Math.abs(portal.getHeight() - other.getHeight()) > 1.0e-6) continue;
            return other;
        }
        return null;
    }

    private static boolean ipl$hasAnySession(UUID shipId, Portal face) {
        StraddleKey key = new StraddleKey(shipId, face.getUUID());
        return IplAtlasStraddleSession.hasSessionKey(key);
    }

    /**
     * A new session belongs to the signed direction of the retained swept trail. The
     * trail covers a physics segment which may have completed before this controller
     * observes a stationary B pose. `resetTrail` after a complete exit makes that old
     * segment unavailable to the opposite face on the next approach.
     */
    private static boolean ipl$ownsCoincidentFace(
        Portal face, Portal twin, PortalCrossingDetector.CrossingState state, boolean haveSession
    ) {
        if (twin == null) return true;
        if (haveSession) return true;
        return switch (state.sweptEntryDirection()) {
            case TOWARD_DESTINATION -> true;
            case TOWARD_SOURCE -> false;
            // No directional physical crossing exists: never manufacture a portal face.
            case AMBIGUOUS, NONE -> false;
        };
    }

    /** Existing session pins its face; only forward motion may finish its parent handoff. */
    private static boolean ipl$continuesThroughOwnedFace(
        PortalCrossingDetector.CrossingState state, boolean haveSession
    ) {
        return haveSession
            ? state.currentSweepDirection() != PortalCrossingDetector.SweepDirection.TOWARD_SOURCE
            : state.sweptEntryDirection() == PortalCrossingDetector.SweepDirection.TOWARD_DESTINATION;
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

    private record TransitCandidate(
        ServerSubLevel airship, Portal portal, boolean hadSession,
        java.util.List<ServerSubLevel> mates
    ) {}
}
