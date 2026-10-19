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

/** Server-authoritative finite-aperture transit orchestration.
 * Phases: snapshot all bodies -> derive sessions/candidates -> reap stale sessions
 * -> chronological group commits -> evaluate mapped residual, at most 16 rounds.
 * Predictive seam arming never proves physical entry. Rigid mates wait until all
 * occupied volumes clear the entrance in the same chart before any body is moved.
 */
public final class SableTransitController {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    private static final double PORTAL_QUERY_INFLATION = 4.0;

    private static final double APERTURE_MARGIN = 0.0;
    private static final int ACTOR_RESYNC_INTERVAL = 100;
    public static final double DEFAULT_EARLY_OPEN_SEGMENTS = 1.0;
    private static volatile double earlyOpenSegments = DEFAULT_EARLY_OPEN_SEGMENTS;

    public static double getEarlyOpenSegments() {
        return earlyOpenSegments;
    }

    public static double setEarlyOpenSegments(double value) {
        if (Double.isNaN(value)) return earlyOpenSegments;
        earlyOpenSegments = Math.max(0.0, Math.min(16.0, value));
        return earlyOpenSegments;
    }

    private static long hostedTransitTick;
    private static final int MAX_TRANSITS_PER_TICK = 16;

    private SableTransitController() {}

    public static void onServerStopping(MinecraftServer server) {
        SableRehomeOps.clearPendingHandoffs();

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

    public static void onContainerTick(ServerSubLevelContainer container) {
        onContainerTick(container, 0);
    }

    private static void onContainerTick(ServerSubLevelContainer container, int round) {
        boolean continuation = round > 0;
        ServerLevel level = (ServerLevel) container.getLevel();
        if (level == null) return;
        level.getProfiler().push("ipl_sable_transit");
        if (!continuation && IplDimAgnostic.isHostingLevel(level)) {
            IplEnteringVolumeVisualization.beginTick();
            PortalCrossingDetector.beginTrailTick();
            IplPortalVolumeCache.beginTick();
        }

        if (!continuation) IplPortalRimManager.tick(level);

        if (!continuation && ipl.sable.dim.IplSceneOwnership.isEnabled()
            && ipl.sable.dim.IplDimAgnostic.isHostingLevel(level)) {
            ipl.sable.dim.IplSceneOwnership.reconcile(container);
        }

        java.util.Set<StraddleKey> seenHostedKeys = new java.util.HashSet<>();

        if (!continuation) {
            for (SubLevel sub : container.getAllSubLevels()) {
                if (!(sub instanceof ServerSubLevel body) || body.isRemoved()
                    || !IplDimAgnostic.isHosted(body)) continue;
                ServerLevel parent = IplDimAgnostic.getServerParentLevel(body);
                if (parent == null) continue;
                IplPortalVolumeCache.touch(body);
                IplEnteringVolumeVisualization.captureTick(body, parent);
                PortalCrossingDetector.captureTrail(body);
                ipl$settleExit(level, body);
            }
        }

        List<TransitCandidate> candidates = null;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel airship)) continue;
            if (airship.isRemoved()) continue;

            boolean hosted = IplDimAgnostic.isHosted(airship);
            if (!hosted) {
                continue;
            }
            if (!continuation && hostedTransitTick % ACTOR_RESYNC_INTERVAL == 0) {
                int rebound = SableTransitOps.resyncStaleActors(airship);
                if (rebound > 0) {
                    LOG.info("[IPL-TRANSIT] rebound {} stale physics actor(s) to live block "
                        + "entities for uuid={}", rebound, airship.getUniqueId());
                }
            }
            IplPortalVolumeCache.touch(airship);

            ServerLevel portalQueryLevel = IplDimAgnostic.getServerParentLevel(airship);
            if (portalQueryLevel == null) continue;

            AABB airshipAabb = PortalCrossingDetector.sweptBounds(airship)
                .inflate(PORTAL_QUERY_INFLATION);
            List<Portal> nearby = new ArrayList<>(portalQueryLevel.getEntitiesOfClass(
                Portal.class,
                airshipAabb,
                Portal::isTeleportable
            ));

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

            IplAtlasStraddleSession.forEachSessionFrom(airship, portalQueryLevel, (sessionPortal, mapping) -> {
                if (sessionPortal.isRemoved()) return;
                for (Portal candidate : nearby) {
                    if (candidate.getUUID().equals(sessionPortal.getUUID())) return;
                }
                nearby.add(sessionPortal);
            });

            nearby.sort(Comparator.comparing(portal -> portal.getUUID().toString()));
            java.util.Set<UUID> resolvedDoorways = new java.util.HashSet<>();

            for (Portal portal : nearby) {
                if (resolvedDoorways.contains(portal.getUUID())) continue;
                if (!ipl$isCanonicalEntranceFace(airship.getUniqueId(), portal, nearby)) continue;
                if (Math.abs(portal.getScaling() - 1.0) > 1e-9
                    || portal.level() != portalQueryLevel
                    || level.getServer().getLevel(portal.getDestDim()) == null
                    || IplDimAgnostic.isHostingLevel(level.getServer().getLevel(portal.getDestDim()))) continue;

                UUID portalUuid = portal.getUUID();

                if (IplShipPortalAnchor.isAnchorShip(portal, airship.getUniqueId())) {
                    continue;
                }
                StraddleKey key = new StraddleKey(
                    airship.getUniqueId(), portalUuid
                );
                boolean haveSession = IplAtlasStraddleSession.hasSessionKey(key);

                Vec3 normal = portal.getNormal().scale(-1.0);
                PortalCrossingDetector.CrossingState state = PortalCrossingDetector.evaluate(
                    airship, portal, normal, APERTURE_MARGIN);

                // A predictive session becomes authoritative only after the real sweep hits.
                boolean physicalEntry = state.startedBeforePortalPlane()
                    && state.sweptIntersectsPortalAperture()
                    && state.sweptEntryDirection() == PortalCrossingDetector.SweepDirection.TOWARD_DESTINATION;
                if (haveSession && physicalEntry) IplAtlasStraddleSession.markAdmitted(key);
                boolean finiteCompletion = PortalCrossingDetector.completesThroughAperture(airship, portal);
                if (haveSession && PortalCrossingDetector.hasSourceGeometry(airship, portal)) {
                    IplAtlasStraddleSession.revokeCompletion(key);
                }
                if (haveSession && finiteCompletion) IplAtlasStraddleSession.markCompleted(key);

                double lookahead = earlyOpenSegments;
                boolean predictedEntry = lookahead > 0.0
                    && state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING
                    && PortalCrossingDetector.willEnterAperture(
                        airship, portal, normal, APERTURE_MARGIN, lookahead);
                boolean straddlingAperture = (state.phase()
                    == PortalCrossingDetector.CrossingPhase.STRADDLING
                    && state.currentIntersectsPortalAperture())
                    || predictedEntry;

                if (straddlingAperture) {
                    Portal twin = ipl$oppositeCoincidentFace(portal, nearby);

                    if (!ipl$ownsCoincidentFace(portal, twin, state, haveSession, predictedEntry)) {
                        continue;
                    }

                    boolean twinHasSession = twin != null
                        && ipl$hasAnySession(airship.getUniqueId(), twin);
                    if (!haveSession && twinHasSession) {
                        continue;
                    }
                    if (haveSession && twinHasSession) {

                        LOG.warn("[IPL-TRANSIT] dual-parity sessions on coincident "
                            + "faces for uuid={} — clearing duplicate face {}",
                            airship.getUniqueId(), portalUuid);
                        IplAtlasStraddleSession.clear(key, "dual-parity-heal");
                        IplStraddleSessionSync.onSessionEnd(
                            level.getServer(), key, "dual-parity-heal");
                        continue;
                    }
                    IplEnteringVolumeVisualization.clearBuffer(airship);

                    IplAtlasStraddleSession.onStraddleTick(airship, portal, normal);
                    if (IplAtlasStraddleSession.hasSessionKey(key)) {
                        if (physicalEntry) IplAtlasStraddleSession.markAdmitted(key);
                        // Zero-plane exit can precede the 0.1 phase hysteresis by a tick.
                        if (finiteCompletion) IplAtlasStraddleSession.markCompleted(key);
                        seenHostedKeys.add(key);
                        IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                    }
                    continue;
                }

                boolean startedSourceSide = state.startedBeforePortalPlane()
                    || PortalCrossingDetector.hasBufferedExit(airship);
                boolean completedOwnedCrossing = IplAtlasStraddleSession.isCompleted(key)
                    || finiteCompletion && ((haveSession && IplAtlasStraddleSession.isAdmitted(key))
                        || startedSourceSide && state.sweptIntersectsPortalAperture()
                            && state.sweptTowardDestination());
                if (state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED
                    && completedOwnedCrossing) {

                    Portal twin = ipl$oppositeCoincidentFace(portal, nearby);
                    if (!haveSession && twin != null
                        && ipl$hasAnySession(airship.getUniqueId(), twin)) {

                        continue;
                    }
                    if (!ipl$ownsCoincidentFace(portal, twin, state, haveSession, false)) {
                        continue;
                    }

                    if (haveSession) {
                        IplAtlasStraddleSession.markCompleted(key);
                        seenHostedKeys.add(key);
                    }

                    if (!haveSession
                        && (PortalCrossingDetector.entryWithinExitSlab(airship, state.entryTime())
                            || PortalCrossingDetector.withinExitClearance(airship, portal)
                            || (twin != null
                                && PortalCrossingDetector.withinExitClearance(airship, twin)))) {
                        continue;
                    }
                    List<ServerSubLevel> mates = ipl$rigidGroupMates(airship);
                    if (mates == null) continue; // Failed joint walk is NOT a solo body.
                    if (!ipl$groupReady(airship, mates, portal)) {
                        // Keep the leading member's destination image until its mates clear.

                        IplAtlasStraddleSession.onStraddleTick(airship, portal, normal);
                        if (IplAtlasStraddleSession.hasSessionKey(key)) {
                            IplAtlasStraddleSession.markCompleted(key);
                            seenHostedKeys.add(key);
                            IplStraddleSessionSync.onSessionStart(level.getServer(), airship, portal);
                        }
                        continue;
                    }
                    double completion = PortalCrossingDetector.completionTime(airship, portal);
                    for (ServerSubLevel mate : mates) {
                        completion = Math.max(completion,
                            PortalCrossingDetector.completionTime(mate, portal));
                    }
                    if (candidates == null) candidates = new ArrayList<>(1);
                    candidates.add(new TransitCandidate(
                        airship, portal, mates, state.entryTime(), completion));

                } else {
                    String reason = state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING
                        ? "backed-out" : "left-aperture";
                    if (haveSession) {

                        if (PortalCrossingDetector.touchesPortalVirtually(airship, portal)) {
                            seenHostedKeys.add(key);
                            IplAtlasStraddleSession.onStraddleTick(airship, portal, normal);
                            continue;
                        }
                        IplAtlasStraddleSession.clear(key, reason);
                        IplStraddleSessionSync.onSessionEnd(level.getServer(), key, reason);
                        // Retiring a source-side session consumes THIS doorway only.
                        // Its coincident back face may not reuse the withdrawing segment;
                        // unrelated portals must still see that segment in this tick.
                        for (Portal face : nearby) {
                            if (ipl$sameRectangle(portal, face)) resolvedDoorways.add(face.getUUID());
                        }
                        IplEnteringVolumeVisualization.replaceBuffer(airship);

                        continue;
                    }

                    IplStraddleSessionSync.onSessionEnd(level.getServer(), key, reason);
                }

            }

        }

        if (IplDimAgnostic.isHostingLevel(level)) {
            java.util.Set<StraddleKey> live = new java.util.HashSet<>(
                IplAtlasStraddleSession.sessionKeys());

            live.addAll(IplStraddleSessionSync.activeKeys());
            for (StraddleKey k : live) {
                if (!seenHostedKeys.contains(k)) {
                    IplAtlasStraddleSession.clear(k, "reaped");
                    IplStraddleSessionSync.onSessionEnd(level.getServer(), k, "reaped");
                }
            }
            if (!continuation) {
                PortalCrossingDetector.pruneTrails();
                IplPortalVolumeCache.prune();
                hostedTransitTick++;
            }
        }

        if (candidates == null) {
            if (!continuation && IplDimAgnostic.isHostingLevel(level)) {
                IplEnteringVolumeVisualization.flush(level.getServer());
                SableRehomeOps.flushParentHandoffs(level.getServer());
            }
            level.getProfiler().pop();
            return;
        }

        // Select physical travel order, not entity iteration order or portal UUID.
        candidates.sort(Comparator
            .comparingDouble((TransitCandidate c) -> Double.isFinite(c.entryTime()) ? c.entryTime() : 0.0)
            .thenComparing(c -> c.portal().getUUID().toString())
            .thenComparing(c -> c.airship().getUniqueId().toString()));
        java.util.Set<UUID> flippedThisTick = new java.util.HashSet<>();
        java.util.Set<UUID> movedRiders = new java.util.HashSet<>();
        for (TransitCandidate c : candidates) {
            UUID uuid = c.airship.getUniqueId();

            if (flippedThisTick.contains(uuid)
                || c.mates().stream().anyMatch(m -> flippedThisTick.contains(m.getUniqueId()))) continue;
            if (!ipl$groupReady(c.airship(), c.mates(), c.portal())
                || !SableRehomeOps.canExecuteHostedTransit(c.airship(), c.portal())
                || c.mates().stream().anyMatch(m -> !SableRehomeOps.canExecuteHostedTransit(m, c.portal()))) continue;
            try {
                boolean flipped = SableRehomeOps.executeHostedTransit(c.airship, c.portal, movedRiders);
                if (flipped) {
                    flippedThisTick.add(uuid);

                    IplEnteringVolumeVisualization.clearBuffer(c.airship);
                    PortalCrossingDetector.rebaseTrailThroughPortal(
                        c.airship, c.portal, c.completionTime());

                    IplGrabChain.onBodyTransit(level.getServer(), c.airship.getUniqueId(), c.portal);

                    IplRopePortalSeam.onShipTransit(c.airship, c.portal);

                    for (ServerSubLevel mate : c.mates()) {
                        UUID mateId = mate.getUniqueId();
                        if (mate.isRemoved() || !flippedThisTick.add(mateId)) continue;
                        boolean mateFlipped = SableRehomeOps.executeHostedTransit(mate, c.portal, movedRiders);
                        if (!mateFlipped) {
                            flippedThisTick.remove(mateId);
                            throw new IllegalStateException("Joint member declined after successful preflight: " + mateId);
                        }
                        PortalCrossingDetector.rebaseTrailThroughPortal(
                            mate, c.portal, c.completionTime());
                        IplGrabChain.onBodyTransit(level.getServer(), mateId, c.portal);
                        IplRopePortalSeam.onShipTransit(mate, c.portal);
                    }

                    ipl$settleExit(level, c.airship);
                    for (ServerSubLevel mate : c.mates()) {
                        if (!mate.isRemoved()) ipl$settleExit(level, mate);
                    }
                }
            } catch (Throwable t) {

                throw new IllegalStateException("Portal joint-group commit failed for " + uuid, t);
            }
        }
        // No new physics step or clock advancement while consuming this residual.
        if (!flippedThisTick.isEmpty() && round + 1 < MAX_TRANSITS_PER_TICK) {

            onContainerTick(container, round + 1);
        } else if (!flippedThisTick.isEmpty() && hostedTransitTick % 100 == 0) {
            LOG.warn("[IPL-TRANSIT] residual crossing budget {} reached; retaining history", MAX_TRANSITS_PER_TICK);
        }
        if (!continuation && IplDimAgnostic.isHostingLevel(level)) {
            IplEnteringVolumeVisualization.flush(level.getServer());
            SableRehomeOps.flushParentHandoffs(level.getServer());
        }
        level.getProfiler().pop();
    }

    private static boolean ipl$groupReady(ServerSubLevel body, List<ServerSubLevel> mates, Portal portal) {
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(body);
        if (parent == null || portal.level() != parent || portal.isRemoved()) return false;
        Vec3 normal = portal.getNormal().scale(-1.0);
        for (ServerSubLevel member : mates) {
            if (member.isRemoved() || !IplDimAgnostic.isHosted(member)
                || IplDimAgnostic.getServerParentLevel(member) != parent
                || IplShipPortalAnchor.isAnchorShip(portal, member.getUniqueId())
                || PortalCrossingDetector.evaluate(member, portal, normal, APERTURE_MARGIN).phase()
                    != PortalCrossingDetector.CrossingPhase.CROSSED
                || (!IplAtlasStraddleSession.isCompleted(new StraddleKey(member.getUniqueId(), portal.getUUID()))
                    && !PortalCrossingDetector.completesThroughAperture(member, portal))) return false;
        }
        return PortalCrossingDetector.evaluate(body, portal, normal, APERTURE_MARGIN).phase()
            == PortalCrossingDetector.CrossingPhase.CROSSED;
    }

    private static java.util.List<ServerSubLevel> ipl$rigidGroupMates(ServerSubLevel body) {
        if (!ipl.sable.natives.IplRapierNatives.isAvailable()) return null;
        try {
            long scene = ipl.sable.dim.IplSceneOwnership
                .liveSceneHandle((ServerLevel) body.getLevel());
            if (scene == 0) return null;
            int[] ids = ipl.sable.natives.IplRapierNatives.connectedSableBodyIds(
                scene, dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(body), true);
            if (ids.length == 0) return null;
            if (ids.length == 1) return ids[0] == body.getRuntimeId() ? java.util.List.of() : null;

            var hosting = dev.ryanhcode.sable.api.sublevel.SubLevelContainer
                .getContainer(body.getLevel());
            if (hosting == null) return null;
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
            if (mates.size() != ids.length - 1) return null;
            mates.sort(Comparator.comparing(m -> m.getUniqueId().toString()));
            return mates;
        } catch (Throwable t) {
            LOG.warn("[IPL-TRANSIT] rigid-group walk failed for uuid={}",
                body.getUniqueId(), t);
            return null;
        }
    }

    private static Portal ipl$oppositeCoincidentFace(Portal portal, List<Portal> candidates) {
        qouteall.imm_ptl.core.portal.PortalExtension extension =
            qouteall.imm_ptl.core.portal.PortalExtension.get(portal);
        Portal flipped = extension.flippedPortal;
        if (flipped == null && extension.flippedPortalId != null
            && portal.level() instanceof ServerLevel level
            && level.getEntity(extension.flippedPortalId) instanceof Portal byId) {
            flipped = byId;
        }
        if (flipped != null && !flipped.isRemoved()
            && ipl$sameRectangle(portal, flipped)
            && portal.getNormal().dot(flipped.getNormal()) < -0.999999) return flipped;
        for (Portal other : candidates) {
            if (other == portal) continue;
            if (!ipl$sameRectangle(portal, other)) continue;
            if (portal.getNormal().dot(other.getNormal()) > -0.999999) continue;
            return other;
        }
        return null;
    }

    private static boolean ipl$sameRectangle(Portal a, Portal b) {
        if (a.level() != b.level() || a.getOriginPos().distanceToSqr(b.getOriginPos()) > 1e-12
            || Math.abs(a.getNormal().dot(b.getNormal())) < .999999) return false;
        boolean direct = Math.abs(a.getWidth() - b.getWidth()) < 1e-6
            && Math.abs(a.getHeight() - b.getHeight()) < 1e-6
            && Math.abs(a.getAxisW().dot(b.getAxisW())) > .999999
            && Math.abs(a.getAxisH().dot(b.getAxisH())) > .999999;
        boolean swapped = Math.abs(a.getWidth() - b.getHeight()) < 1e-6
            && Math.abs(a.getHeight() - b.getWidth()) < 1e-6
            && Math.abs(a.getAxisW().dot(b.getAxisH())) > .999999
            && Math.abs(a.getAxisH().dot(b.getAxisW())) > .999999;
        return direct || swapped;
    }

    private static void ipl$settleExit(ServerLevel level, ServerSubLevel body) {
        UUID exited = PortalCrossingDetector.settleExit(body);
        if (exited == null) return;
        StraddleKey key = new StraddleKey(body.getUniqueId(), exited);
        if (IplAtlasStraddleSession.hasSessionKey(key)) {
            IplAtlasStraddleSession.clear(key, "exit-cleared");
        }
        IplStraddleSessionSync.onSessionEnd(level.getServer(), key, "exit-cleared");

        IplEnteringVolumeVisualization.replaceBuffer(body);
    }

    private static boolean ipl$hasAnySession(UUID shipId, Portal face) {
        StraddleKey key = new StraddleKey(shipId, face.getUUID());
        return IplAtlasStraddleSession.hasSessionKey(key);
    }

    private static boolean ipl$ownsCoincidentFace(
        Portal face, Portal twin, PortalCrossingDetector.CrossingState state,
        boolean haveSession, boolean predictedEntry
    ) {
        if (haveSession) return true;

        if (predictedEntry) return true;
        return switch (state.sweptEntryDirection()) {
            case TOWARD_DESTINATION -> true;
            case TOWARD_SOURCE -> false;

            case AMBIGUOUS, NONE -> false;
        };
    }

    private static boolean ipl$isCanonicalEntranceFace(UUID shipId, Portal portal, List<Portal> candidates) {
        for (Portal other : candidates) {
            if (other == portal || !portal.getDestDim().equals(other.getDestDim())) continue;
            if (!ipl$sameRectangle(portal, other)) continue;
            if (portal.getOriginPos().distanceToSqr(other.getOriginPos()) > 1.0e-12
                || portal.getDestPos().distanceToSqr(other.getDestPos()) > 1.0e-12
                || Math.abs(portal.getWidth() - other.getWidth()) > 1.0e-6
                || Math.abs(portal.getHeight() - other.getHeight()) > 1.0e-6
                || portal.getNormal().dot(other.getNormal()) < 0.999999) {
                continue;
            }
            var mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
            var otherMapping = IplStraddlePoseMap.StraddleMapping.of(other);
            if (mapping.mapVec(new Vec3(1, 0, 0)).distanceToSqr(otherMapping.mapVec(new Vec3(1, 0, 0))) > 1e-12
                || mapping.mapVec(new Vec3(0, 1, 0)).distanceToSqr(otherMapping.mapVec(new Vec3(0, 1, 0))) > 1e-12
                || Math.abs(portal.getScaling() - other.getScaling()) > 1e-9) continue;
            boolean ownSession = IplAtlasStraddleSession.hasSessionKey(new StraddleKey(shipId, portal.getUUID()));
            boolean otherSession = IplAtlasStraddleSession.hasSessionKey(new StraddleKey(shipId, other.getUUID()));
            if (ownSession != otherSession) {
                if (otherSession) return false;
                continue;
            }
            if (portal.getUUID().toString().compareTo(other.getUUID().toString()) > 0) {
                return false;
            }
        }
        return true;
    }

    private record TransitCandidate(
        ServerSubLevel airship, Portal portal,
        java.util.List<ServerSubLevel> mates,
        double entryTime,
        double completionTime
    ) {}
}
