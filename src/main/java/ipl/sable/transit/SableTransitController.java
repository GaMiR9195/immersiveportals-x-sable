package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Portal transit for Sable sub-levels in the native model (each sub-level lives in its
 * own level). Runs at the TAIL of every level's sub-level container tick and handles the
 * ships of that level:
 *
 * <ol>
 *   <li>capture the swept trail (once per server tick per ship);</li>
 *   <li>per nearby portal: approach / straddle / crossed classification of the swept
 *       volume; straddling opens a projection session;</li>
 *   <li>chained sessions: the same classification run on the projection side (the
 *       trail mapped through the session isometry) against portals of the
 *       destination level;</li>
 *   <li>a finished crossing flips the real body to the far side, unless the
 *       {@link IplAttachmentGate} says it is attached; flipped bodies are re-processed
 *       immediately so chains continue seamlessly (infinite portal loops included).</li>
 * </ol>
 */
public final class SableTransitController {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    private static final double PORTAL_QUERY_INFLATION = 4.0;
    private static final double APERTURE_MARGIN = 0.0;
    private static final int ACTOR_RESYNC_INTERVAL = 100;
    /** Flips per ship per tick (portal loops at extreme speed); history is kept beyond it. */
    private static final int MAX_TRANSITS_PER_TICK = 16;

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

    private static int lastServerTick = Integer.MIN_VALUE;
    private static long transitTick;

    private SableTransitController() {}

    public static void onServerStopping(MinecraftServer server) {
        IplShipFlip.clearPending();
        IplGrabChain.clearAll();
        IplStraddleSessionSync.clearAll();
        IplPortalRimManager.clearAll();
        IplAtlasStraddleSession.clearAll();
        IplPortalVolumeCache.clear();
        PortalCrossingDetector.clearTrails();
        IplShipPortalAnchor.clearAll();
        IplShipNetherPortal.clearPending();
        IplAttachmentGate.clearAll();
        lastServerTick = Integer.MIN_VALUE;
    }

    /** Once per server tick, at the first container tick of the tick. */
    private static void beginServerTick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick == lastServerTick) return;
        lastServerTick = tick;
        PortalCrossingDetector.pruneTrails();
        IplPortalVolumeCache.prune();
        PortalCrossingDetector.beginTrailTick();
        IplPortalVolumeCache.beginTick();
        IplEnteringVolumeVisualization.beginTick();
        transitTick++;
        // Sessions of bodies that disappeared (unloaded, removed, merged).
        for (StraddleKey key : IplAtlasStraddleSession.sessionKeys()) {
            if (!IplAtlasStraddleSession.hasSessionKey(key)) continue;
            Portal portal = IplAtlasStraddleSession.sessionPortal(key);
            if (portal == null || portal.isRemoved() || !shipAlive(server, key.subLevelUuid())) {
                endSession(server, key, "stale");
            }
        }
    }

    private static boolean shipAlive(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(uuid) instanceof ServerSubLevel sub && !sub.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    public static void onContainerTick(ServerSubLevelContainer container) {
        if (!(container.getLevel() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        level.getProfiler().push("ipl_sable_transit");
        try {
            beginServerTick(server);
            IplPortalRimManager.tick(level);
            List<ServerSubLevel> ships = new ArrayList<>();
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub instanceof ServerSubLevel body && !body.isRemoved()) ships.add(body);
            }
            if (ships.isEmpty() && IplAtlasStraddleSession.sessionKeys().isEmpty()) return;
            for (ServerSubLevel ship : ships) {
                IplPortalVolumeCache.touch(ship);
                IplEnteringVolumeVisualization.captureTick(ship, level);
                PortalCrossingDetector.captureTrail(ship);
                ipl$settleExit(level, ship);
            }
            Set<StraddleKey> seen = new HashSet<>();
            java.util.Map<UUID, UUID> movedRiders = new java.util.HashMap<>();
            List<ServerSubLevel> pending = ships;
            for (int round = 0; round < MAX_TRANSITS_PER_TICK && !pending.isEmpty(); round++) {
                List<TransitCandidate> candidates = new ArrayList<>();
                for (ServerSubLevel ship : pending) {
                    if (!ship.isRemoved()) processShip(ship, round == 0, seen, candidates);
                }
                pending = executeCandidates(server, candidates, movedRiders);
                if (round == MAX_TRANSITS_PER_TICK - 1 && !pending.isEmpty() && transitTick % 100 == 0) {
                    LOG.warn("[IPL-TRANSIT] residual crossing budget {} reached; history retained",
                        MAX_TRANSITS_PER_TICK);
                }
            }
            // Reap sessions of this level's ships that no evaluation kept alive this tick.
            for (ServerSubLevel ship : ships) {
                if (ship.isRemoved() || ship.getLevel() != level) continue;
                for (StraddleKey key : IplAtlasStraddleSession.sessionKeysFor(ship.getUniqueId())) {
                    if (!seen.contains(key) && IplAtlasStraddleSession.hasSessionKey(key)) {
                        endSession(server, key, "reaped");
                    }
                }
                IplStraddleSessionSync.retainOnly(server, ship.getUniqueId(), seen);
            }
            IplEnteringVolumeVisualization.flush(server);
            IplShipFlip.flushHandoffs(server);
        } finally {
            level.getProfiler().pop();
        }
    }

    /** Ends a session, its chained descendants, and their client mirrors. */
    static void endSession(MinecraftServer server, StraddleKey key, String reason) {
        String prefix = key.path();
        for (StraddleKey other : IplAtlasStraddleSession.sessionKeysFor(key.subLevelUuid())) {
            if (other.equals(key) || other.via().equals(prefix) || other.via().startsWith(prefix + ">")) {
                IplStraddleSessionSync.onSessionEnd(server, other, reason);
            }
        }
        IplStraddleSessionSync.onSessionEnd(server, key, reason);
        IplAtlasStraddleSession.clear(key, reason);
    }

    private static List<Portal> portalsNear(ServerLevel level, AABB swept) {
        AABB query = swept.inflate(PORTAL_QUERY_INFLATION);
        List<Portal> nearby = new ArrayList<>(level.getEntitiesOfClass(Portal.class, query, Portal::isTeleportable));
        Vec3 center = query.getCenter();
        double reach = 0.5 * Math.sqrt(query.getXsize() * query.getXsize()
            + query.getYsize() * query.getYsize() + query.getZsize() * query.getZsize());
        for (Portal global : qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage.getGlobalPortals(level)) {
            if (global.isTeleportable() && global.getDistanceToNearestPointInPortal(center) <= reach) {
                nearby.add(global);
            }
        }
        return nearby;
    }

    private static boolean usablePortal(MinecraftServer server, Portal portal, ServerLevel level) {
        return Math.abs(portal.getScaling() - 1.0) <= 1e-9 && portal.level() == level
            && !portal.isRemoved() && server.getLevel(portal.getDestDim()) != null;
    }

    private static void processShip(ServerSubLevel airship, boolean firstRound,
                                     Set<StraddleKey> seen, List<TransitCandidate> candidates) {
        ServerLevel level = airship.getLevel();
        MinecraftServer server = level.getServer();
        if (firstRound && transitTick % ACTOR_RESYNC_INTERVAL == 0) {
            int rebound = SableTransitOps.resyncStaleActors(airship);
            if (rebound > 0) {
                LOG.debug("[IPL-TRANSIT] rebound {} stale physics actor(s) for {}", rebound, airship.getUniqueId());
            }
        }
        IplPortalVolumeCache.touch(airship);
        List<Portal> nearby = portalsNear(level, PortalCrossingDetector.sweptBounds(airship));
        IplAtlasStraddleSession.forEachSessionFrom(airship, level, (sessionPortal, mapping) -> {
            if (sessionPortal.isRemoved()) return;
            for (Portal candidate : nearby) if (candidate.getUUID().equals(sessionPortal.getUUID())) return;
            nearby.add(sessionPortal);
        });
        nearby.sort(Comparator.comparing(portal -> portal.getUUID().toString()));
        Set<UUID> resolvedDoorways = new HashSet<>();
        for (Portal portal : nearby) {
            // Русский: корневой тест портала видит только родной кусок тела и кусок, уже
            // ушедший в ЭТОТ портал (см. PortalCrossingDetector.PieceScope).
            PortalCrossingDetector.enterPieceScope("", portal.getUUID());
            try {
            if (resolvedDoorways.contains(portal.getUUID())) continue;
            if (!ipl$isCanonicalEntranceFace(airship.getUniqueId(), portal, nearby)) continue;
            if (!usablePortal(server, portal, level)) continue;
            if (IplShipPortalAnchor.isAnchorShip(portal, airship.getUniqueId())) continue;
            UUID portalUuid = portal.getUUID();
            StraddleKey key = new StraddleKey(airship.getUniqueId(), portalUuid);
            boolean haveSession = IplAtlasStraddleSession.hasSessionKey(key);
            Vec3 normal = portal.getNormal().scale(-1.0);
            PortalCrossingDetector.CrossingState state =
                PortalCrossingDetector.evaluate(airship, portal, normal, APERTURE_MARGIN);
            boolean physicalEntry = state.provesPhysicalEntry();
            if (haveSession && physicalEntry) IplAtlasStraddleSession.markAdmitted(key);
            boolean admitted = physicalEntry || haveSession && IplAtlasStraddleSession.isAdmitted(key);
            boolean finiteCompletion = state.phase() != PortalCrossingDetector.CrossingPhase.APPROACHING
                && (haveSession || state.sweptIntersectsPortalAperture())
                && PortalCrossingDetector.completesThroughAperture(airship, portal,
                    admitted ? PortalCrossingDetector.SESSION_RELEASE_MARGIN : 0.0);
            boolean hasSource = haveSession && PortalCrossingDetector.hasSourceGeometry(airship, portal);
            if (hasSource) IplAtlasStraddleSession.revokeCompletion(key);
            if (haveSession && finiteCompletion) IplAtlasStraddleSession.markCompleted(key);
            double lookahead = earlyOpenSegments;
            boolean predictedEntry = lookahead > 0.0
                && state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING
                && PortalCrossingDetector.willEnterAperture(airship, portal, normal, APERTURE_MARGIN, lookahead);
            boolean straddling = state.phase() == PortalCrossingDetector.CrossingPhase.STRADDLING;
            boolean retains = straddling && admitted && PortalCrossingDetector.retainsAdmittedSession(airship, portal);
            boolean heldComplete = haveSession && !hasSource && IplAtlasStraddleSession.isCompleted(key);
            boolean straddlingAperture = (straddling
                && ((admitted ? retains : state.currentIntersectsPortalAperture()) || heldComplete))
                || predictedEntry;
            // Русский: "широкая" область (полупространство) только когда вход доказан и все
            // сечения тела в проёме, либо тело уже целиком за плоскостью. Предсказанное
            // открытие и непризнанный вход остаются трубками.
            boolean wide = straddling && (retains || heldComplete);
            if (straddlingAperture) {
                Portal twin = ipl$oppositeCoincidentFace(portal, nearby);
                if (!ipl$ownsCoincidentFace(portal, twin, state, haveSession, predictedEntry)) continue;
                boolean twinHasSession = twin != null && ipl$hasAnySession(airship.getUniqueId(), twin);
                if (!haveSession && twinHasSession) continue;
                if (haveSession && twinHasSession) {
                    LOG.debug("[IPL-TRANSIT] dual-parity sessions for {} — dropping face {}",
                        airship.getUniqueId(), portalUuid);
                    endSession(server, key, "dual-parity-heal");
                    continue;
                }
                IplEnteringVolumeVisualization.clearBuffer(airship);
                keepRootSession(server, airship, portal, normal, key, seen, physicalEntry, finiteCompletion, wide);
                continue;
            }
            boolean startedSourceSide = state.startedBeforePortalPlane()
                || PortalCrossingDetector.hasBufferedExit(airship);
            boolean completedOwnedCrossing = IplAtlasStraddleSession.isCompleted(key)
                || finiteCompletion && ((haveSession && IplAtlasStraddleSession.isAdmitted(key))
                    || startedSourceSide && state.sweptIntersectsPortalAperture()
                        && state.sweptTowardDestination());
            if (state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED && completedOwnedCrossing) {
                Portal twin = ipl$oppositeCoincidentFace(portal, nearby);
                if (!haveSession && twin != null && ipl$hasAnySession(airship.getUniqueId(), twin)) continue;
                if (!ipl$ownsCoincidentFace(portal, twin, state, haveSession, false)) continue;
                if (haveSession) {
                    IplAtlasStraddleSession.markCompleted(key);
                    seen.add(key);
                }
                if (!haveSession
                    && (PortalCrossingDetector.entryWithinExitSlab(airship, state.entryTime())
                        || PortalCrossingDetector.withinExitClearance(airship, portal)
                        || (twin != null && PortalCrossingDetector.withinExitClearance(airship, twin)))) {
                    continue;
                }
                TransitGroup group = ipl$transitGroup(airship);
                if (group == null) continue; // a failed joint walk is NOT a solo body
                List<ServerSubLevel> mates = group.mates();
                boolean blocked = !ipl$groupReady(airship, mates, portal)
                    || IplAttachmentGate.flipBlocker(airship, mates, group.nativeJointed(),
                        IplShipFlip.isCrossDimension(airship, portal)) != null;
                if (blocked) {
                    // Attached or not ready: the far side keeps living on the projection.
                    keepRootSession(server, airship, portal, normal, key, seen, false, true, true);
                    continue;
                }
                double completion = PortalCrossingDetector.completionTime(airship, portal);
                for (ServerSubLevel mate : mates) {
                    completion = Math.max(completion, PortalCrossingDetector.completionTime(mate, portal));
                }
                candidates.add(new TransitCandidate(airship, portal, mates, state.entryTime(), completion));
            } else {
                String reason = state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING
                    ? "backed-out" : "left-aperture";
                if (haveSession) {
                    if (PortalCrossingDetector.touchesPortalVirtually(airship, portal)) {
                        keepRootSession(server, airship, portal, normal, key, seen, false, false, false);
                        continue;
                    }
                    endSession(server, key, reason);
                    for (Portal face : nearby) {
                        if (ipl$sameRectangle(portal, face)) resolvedDoorways.add(face.getUUID());
                    }
                    IplEnteringVolumeVisualization.replaceBuffer(airship);
                }
            }
            } finally {
                PortalCrossingDetector.exitPieceScope();
            }
        }
        processChains(airship, seen);
    }

    private static void keepRootSession(MinecraftServer server, ServerSubLevel airship, Portal portal, Vec3 normal,
                                        StraddleKey key, Set<StraddleKey> seen,
                                        boolean physicalEntry, boolean completed, boolean wide) {
        IplAtlasStraddleSession.onStraddleTick(airship, portal, normal, wide);
        if (!IplAtlasStraddleSession.hasSessionKey(key)) return;
        if (physicalEntry) IplAtlasStraddleSession.markAdmitted(key);
        if (completed) IplAtlasStraddleSession.markCompleted(key);
        seen.add(key);
        IplStraddleSessionSync.onSessionStart(server, airship, key, portal);
    }

    /**
     * Entry detection on the projection side: every live session's image is evaluated
     * against the portals of its destination level, through the session isometry.
     */
    private static void processChains(ServerSubLevel airship, Set<StraddleKey> seen) {
        MinecraftServer server = airship.getLevel().getServer();
        for (int depth = 1; depth < IplAtlasStraddleSession.MAX_CHAIN_DEPTH; depth++) {
            boolean any = false;
            for (StraddleKey parentKey : IplAtlasStraddleSession.sessionKeysFor(airship.getUniqueId())) {
                if (parentKey.depth() != depth || !seen.contains(parentKey)) continue;
                any = true;
                Portal parentPortal = IplAtlasStraddleSession.sessionPortal(parentKey);
                ServerLevel dest = IplAtlasStraddleSession.sessionDest(parentKey);
                IplStraddlePoseMap.StraddleMapping mapping = IplAtlasStraddleSession.sessionMapping(parentKey);
                if (parentPortal == null || dest == null || mapping == null) continue;
                List<Portal> nearby = PortalCrossingDetector.inMappedView(mapping,
                    () -> portalsNear(dest, PortalCrossingDetector.sweptBounds(airship)));
                for (StraddleKey child : IplAtlasStraddleSession.childrenOf(parentKey)) {
                    Portal existing = IplAtlasStraddleSession.sessionPortal(child);
                    if (existing != null && !nearby.contains(existing)) nearby.add(existing);
                }
                nearby.sort(Comparator.comparing(portal -> portal.getUUID().toString()));
                for (Portal portal : nearby) {
                    if (!usablePortal(server, portal, dest) || ipl$isExitFace(parentPortal, portal)
                        || IplShipPortalAnchor.isAnchorShip(portal, airship.getUniqueId())) continue;
                    StraddleKey childKey = parentKey.child(portal.getUUID());
                    boolean haveChild = IplAtlasStraddleSession.hasSessionKey(childKey);
                    Vec3 normal = portal.getNormal().scale(-1.0);
                    String parentPath = parentKey.path();
                    ChainVerdict verdict = PortalCrossingDetector.inMappedView(mapping, () ->
                        PortalCrossingDetector.inPieceScope(parentPath, portal.getUUID(), () -> {
                            var state = PortalCrossingDetector.evaluate(airship, portal, normal, APERTURE_MARGIN);
                            if (state.phase() == PortalCrossingDetector.CrossingPhase.APPROACHING) {
                                boolean predicted = earlyOpenSegments > 0.0 && PortalCrossingDetector.willEnterAperture(
                                    airship, portal, normal, APERTURE_MARGIN, earlyOpenSegments);
                                return new ChainVerdict(predicted, false, false);
                            }
                            boolean crossed = state.phase() == PortalCrossingDetector.CrossingPhase.CROSSED;
                            if (haveChild) {
                                boolean retains = PortalCrossingDetector.retainsAdmittedSession(airship, portal);
                                boolean completed = crossed
                                    && !PortalCrossingDetector.hasSourceGeometry(airship, portal);
                                return new ChainVerdict(crossed || retains || state.currentIntersectsPortalAperture(),
                                    crossed || retains, completed);
                            }
                            boolean open = state.phase() == PortalCrossingDetector.CrossingPhase.STRADDLING
                                && state.currentIntersectsPortalAperture()
                                && state.startedBeforePortalPlane()
                                && state.sweptEntryDirection() != PortalCrossingDetector.SweepDirection.TOWARD_SOURCE;
                            return new ChainVerdict(open,
                                open && PortalCrossingDetector.retainsAdmittedSession(airship, portal), false);
                        }));
                    if (verdict.keep()) {
                        IplAtlasStraddleSession.onChainTick(parentKey, portal, normal, verdict.wide());
                        if (IplAtlasStraddleSession.hasSessionKey(childKey)) {
                            // Русский: дочерняя сессия открывается только по доказанному входу
                            // образа, поэтому сразу "admitted"; "completed" (образ целиком за
                            // плоскостью) нужен при повышении сессий после флипа родителя.
                            IplAtlasStraddleSession.markAdmitted(childKey);
                            if (verdict.completed()) IplAtlasStraddleSession.markCompleted(childKey);
                            else IplAtlasStraddleSession.revokeCompletion(childKey);
                            seen.add(childKey);
                            IplStraddleSessionSync.onSessionStart(server, airship, childKey, portal);
                        }
                    } else if (haveChild) {
                        endSession(server, childKey, "chain-left");
                    }
                }
            }
            if (!any) break;
        }
    }

    /**
     * The reverse face at a portal's exit (where the image comes out) is never a new entrance.
     *
     * <p>Русский комментарий: раньше "выходной гранью" считался ЛЮБОЙ портал, совпадающий
     * с плоскостью выхода. В ряду порталов "выход P1 = вход P2" (сдвиг на блок, ещё раз на
     * блок...) вход P2 отбрасывался, образ проходил сквозь P2 как сквозь воздух, а после
     * флипа тело оказывалось целиком за плоскостью P2 без сессии и застревало. Выходная
     * грань — только та, что ведёт НАЗАД в исходный портал (обратный портал пары и его
     * перевёрнутая сторона); соосный портал с другим назначением — честный следующий вход.
     */
    private static boolean ipl$isExitFace(Portal entered, Portal candidate) {
        if (candidate.getUUID().equals(entered.getUUID())) return true;
        if (!candidate.level().dimension().equals(entered.getDestDim())) return false;
        if (candidate.getOriginPos().distanceToSqr(entered.getDestPos()) > 1.0e-6) return false;
        Vec3 exitNormal = entered.transformLocalVec(entered.getNormal());
        if (Math.abs(candidate.getNormal().dot(exitNormal)) <= 0.9999) return false;
        return candidate.getDestDim().equals(entered.level().dimension())
            && candidate.getDestPos().distanceToSqr(entered.getOriginPos()) <= 1.0e-4;
    }

    /** Chain evaluation outcome (computed inside the mapped view and piece scope). */
    private record ChainVerdict(boolean keep, boolean wide, boolean completed) {}

    private static List<ServerSubLevel> executeCandidates(MinecraftServer server, List<TransitCandidate> candidates,
                                                          java.util.Map<UUID, UUID> movedRiders) {
        if (candidates.isEmpty()) return List.of();
        candidates.sort(Comparator
            .comparingDouble((TransitCandidate c) -> Double.isFinite(c.entryTime()) ? c.entryTime() : 0.0)
            .thenComparing(c -> c.portal().getUUID().toString())
            .thenComparing(c -> c.airship().getUniqueId().toString()));
        Set<UUID> flipped = new HashSet<>();
        List<ServerSubLevel> continuing = new ArrayList<>();
        for (TransitCandidate c : candidates) {
            UUID uuid = c.airship().getUniqueId();
            if (flipped.contains(uuid) || c.mates().stream().anyMatch(m -> flipped.contains(m.getUniqueId()))) continue;
            if (!ipl$groupReady(c.airship(), c.mates(), c.portal())
                || !IplShipFlip.canFlip(c.airship(), c.portal())
                || c.mates().stream().anyMatch(m -> !IplShipFlip.canFlip(m, c.portal()))) continue;
            var promotions = IplAtlasStraddleSession.promotionsThrough(new StraddleKey(uuid, c.portal().getUUID()));
            ServerSubLevel live = IplShipFlip.flip(c.airship(), c.portal(), movedRiders);
            if (live == null) continue;
            flipped.add(uuid);
            afterFlip(server, live, c.portal(), c.completionTime());
            IplAtlasStraddleSession.promote(live, promotions);
            continuing.add(live);
            for (ServerSubLevel mate : c.mates()) {
                if (mate.isRemoved() || !flipped.add(mate.getUniqueId())) continue;
                var matePromotions = IplAtlasStraddleSession.promotionsThrough(
                    new StraddleKey(mate.getUniqueId(), c.portal().getUUID()));
                ServerSubLevel liveMate = IplShipFlip.flip(mate, c.portal(), movedRiders);
                if (liveMate == null) {
                    LOG.error("[IPL-TRANSIT] joint member {} declined after preflight", mate.getUniqueId());
                    continue;
                }
                afterFlip(server, liveMate, c.portal(), c.completionTime());
                IplAtlasStraddleSession.promote(liveMate, matePromotions);
                continuing.add(liveMate);
            }
        }
        for (ServerSubLevel body : continuing) {
            if (!body.isRemoved()) ipl$settleExit(body.getLevel(), body);
        }
        return continuing;
    }

    private static void afterFlip(MinecraftServer server, ServerSubLevel body, Portal portal, double completion) {
        IplEnteringVolumeVisualization.clearBuffer(body);
        PortalCrossingDetector.rebaseTrailThroughPortal(body, portal, completion);
        IplGrabChain.onBodyTransit(server, body.getUniqueId(), portal);
        IplRopePortalSeam.onShipTransit(body, portal);
    }

    private static boolean ipl$groupReady(ServerSubLevel body, List<ServerSubLevel> mates, Portal portal) {
        return PortalCrossingDetector.inPieceScope("", portal.getUUID(), () -> ipl$groupReadyScoped(body, mates, portal));
    }

    private static boolean ipl$groupReadyScoped(ServerSubLevel body, List<ServerSubLevel> mates, Portal portal) {
        if (portal.level() != body.getLevel() || portal.isRemoved()) return false;
        Vec3 normal = portal.getNormal().scale(-1.0);
        for (ServerSubLevel member : mates) {
            if (member.isRemoved() || member.getLevel() != body.getLevel()
                || IplShipPortalAnchor.isAnchorShip(portal, member.getUniqueId())) return false;
            var state = PortalCrossingDetector.evaluate(member, portal, normal, APERTURE_MARGIN);
            if (state.phase() != PortalCrossingDetector.CrossingPhase.CROSSED) return false;
            StraddleKey key = new StraddleKey(member.getUniqueId(), portal.getUUID());
            boolean admitted = state.provesPhysicalEntry() || IplAtlasStraddleSession.isAdmitted(key);
            if (!IplAtlasStraddleSession.isCompleted(key)
                && !PortalCrossingDetector.completesThroughAperture(member, portal,
                    admitted ? PortalCrossingDetector.SESSION_RELEASE_MARGIN : 0.0)) return false;
        }
        return PortalCrossingDetector.evaluate(body, portal, normal, APERTURE_MARGIN).phase()
            == PortalCrossingDetector.CrossingPhase.CROSSED;
    }

    /** Members crossing together with a body (sorted by UUID) and whether native joints are involved. */
    private record TransitGroup(List<ServerSubLevel> mates, boolean nativeJointed) {}

    /**
     * Native rigid mates plus actor-linked partners (springs, connectors...) of
     * {@code body}, walked transitively (bounded by {@link IplAttachmentGate#MAX_TRANSIT_GROUP});
     * null if a joint walk failed. A partner beyond the bound stays outside the group and
     * the attachment gate then blocks the flip ("linked").
     */
    @org.jetbrains.annotations.Nullable
    private static TransitGroup ipl$transitGroup(ServerSubLevel body) {
        List<ServerSubLevel> rigid = ipl$rigidGroupMates(body);
        if (rigid == null) return null;
        boolean nativeJointed = !rigid.isEmpty();
        java.util.LinkedHashMap<UUID, ServerSubLevel> members = new java.util.LinkedHashMap<>();
        members.put(body.getUniqueId(), body);
        for (ServerSubLevel mate : rigid) members.putIfAbsent(mate.getUniqueId(), mate);
        java.util.ArrayDeque<ServerSubLevel> queue = new java.util.ArrayDeque<>(members.values());
        walk:
        while (!queue.isEmpty()) {
            ServerSubLevel member = queue.poll();
            for (ServerSubLevel partner : IplAttachmentGate.actorPartners(member)) {
                if (members.containsKey(partner.getUniqueId())) continue;
                if (members.size() >= IplAttachmentGate.MAX_TRANSIT_GROUP) break walk;
                List<ServerSubLevel> partnerRigid = ipl$rigidGroupMates(partner);
                if (partnerRigid == null) return null;
                nativeJointed |= !partnerRigid.isEmpty();
                members.put(partner.getUniqueId(), partner);
                queue.add(partner);
                for (ServerSubLevel pm : partnerRigid) {
                    if (members.putIfAbsent(pm.getUniqueId(), pm) == null) queue.add(pm);
                }
            }
        }
        members.remove(body.getUniqueId());
        if (members.isEmpty()) return new TransitGroup(List.of(), nativeJointed);
        List<ServerSubLevel> mates = new ArrayList<>(members.values());
        mates.sort(Comparator.comparing(m -> m.getUniqueId().toString()));
        return new TransitGroup(mates, nativeJointed);
    }

    /** Bodies rigidly jointed to {@code body} (they cross together); null if the walk failed. */
    private static List<ServerSubLevel> ipl$rigidGroupMates(ServerSubLevel body) {
        if (!ipl.sable.natives.IplRapierNatives.isAvailable()) return List.of();
        try {
            long scene = ipl.sable.atlas.IplScenes.sceneHandle(body.getLevel());
            if (scene == 0) return null;
            int[] ids = ipl.sable.natives.IplRapierNatives.connectedSableBodyIds(
                scene, dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(body), true);
            if (ids.length == 0) return null;
            int selfId = body.getRuntimeId();
            if (ids.length == 1) return ids[0] == selfId ? List.of() : null;
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(body.getLevel());
            if (container == null) return null;
            List<ServerSubLevel> mates = new ArrayList<>(ids.length - 1);
            for (int id : ids) {
                if (id == selfId) continue;
                for (var sub : container.getAllSubLevels()) {
                    if (sub.getRuntimeId() == id && !sub.isRemoved() && sub instanceof ServerSubLevel server) {
                        mates.add(server);
                        break;
                    }
                }
            }
            if (mates.size() != ids.length - 1) return null;
            mates.sort(Comparator.comparing(m -> m.getUniqueId().toString()));
            return mates;
        } catch (Throwable t) {
            LOG.debug("[IPL-TRANSIT] rigid-group walk failed for {}", body.getUniqueId(), t);
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
        endSession(level.getServer(), new StraddleKey(body.getUniqueId(), exited), "exit-cleared");

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
