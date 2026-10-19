package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Occupied-block continuous OBB sweep in one coordinate chart.
 * A trail is consumed in chronological order; a portal maps only its residual.
 * Phase hysteresis never moves the actual geometric entry plane.
 */
public final class PortalCrossingDetector {

    private static final double EPSILON = 1.0e-8;
    private static final double PORTAL_HYSTERESIS_DEPTH = 0.1;
    /** Maximum side protrusion of the occupied SECTION at the portal, release only.
     * Quarter-block tolerance never widens fresh admission or native clipping. */
    public static final double SESSION_RELEASE_MARGIN = 0.65;
    private static final Map<UUID, Trail> TRAILS = new HashMap<>();
    private static final Map<UUID, ExitAnchor> EXIT_ANCHORS = new HashMap<>();
    private static final double EXIT_CLEARANCE_THICKNESS = 0.01;
    private static final double MIN_EXIT_CLEARANCE = 1e-3;
    private static final double MAX_ROTATION_STEP = Math.toRadians(5.0);
    private static final Map<UUID, ExitLatch> EXIT_LATCHES = new HashMap<>();
    private static long trailTick;

    private PortalCrossingDetector() {}

    public enum CrossingPhase { APPROACHING, STRADDLING, CROSSED }
    public enum SweepDirection { TOWARD_DESTINATION, TOWARD_SOURCE, AMBIGUOUS, NONE }

    public record CrossingState(
        CrossingPhase phase,
        boolean startedBeforePortalPlane,
        boolean sweptIntersectsPortalAperture,
        boolean currentIntersectsPortalAperture,
        SweepDirection sweptEntryDirection,
        SweepDirection currentSweepDirection,
        double entryTime
    ) {
        public boolean sweptTowardDestination() {
            return sweptEntryDirection == SweepDirection.TOWARD_DESTINATION;
        }

        /** A strict finite sweep may admit and complete within the SAME tick. */
        public boolean provesPhysicalEntry() {
            return startedBeforePortalPlane && sweptIntersectsPortalAperture && sweptTowardDestination();
        }
    }

    public static void beginTrailTick() {
        trailTick++;
    }

    private static ExitLatch liveExitLatch(ServerSubLevel airship) {
        return airship == null ? null : EXIT_LATCHES.get(airship.getUniqueId());
    }

    private static double backwardTravelIntoPlate(ServerSubLevel airship, ExitLatch latch) {
        AABB start = boundsAt(airship, 0.0);
        AABB end = currentBounds(airship);
        double tx = (end.minX + end.maxX) * 0.5 - (start.minX + start.maxX) * 0.5;
        double ty = (end.minY + end.maxY) * 0.5 - (start.minY + start.maxY) * 0.5;
        double tz = (end.minZ + end.maxZ) * 0.5 - (start.minZ + start.maxZ) * 0.5;

        return tx * latch.nx + ty * latch.ny + tz * latch.nz;
    }

    private static final double PLATE_IDENTITY_TOLERANCE = 1.0e-3;

    private static boolean plateIsRectangleOf(
        ExitLatch latch, @org.jetbrains.annotations.Nullable Portal portal
    ) {
        if (portal == null) return false;
        Vec3 o = portal.getOriginPos();
        double dx = o.x - latch.ox;
        double dy = o.y - latch.oy;
        double dz = o.z - latch.oz;
        if (dx * dx + dy * dy + dz * dz
            > PLATE_IDENTITY_TOLERANCE * PLATE_IDENTITY_TOLERANCE) {
            return false;
        }
        Vec3 n = portal.getNormal(), w = portal.getAxisW(), h = portal.getAxisH();
        if (Math.abs(n.x * latch.nx + n.y * latch.ny + n.z * latch.nz) < .999999) return false;
        boolean direct = Math.abs(portal.getWidth() * .5 - latch.halfW) < 1e-6
            && Math.abs(portal.getHeight() * .5 - latch.halfH) < 1e-6
            && Math.abs(w.x*latch.wx + w.y*latch.wy + w.z*latch.wz) > .999999
            && Math.abs(h.x*latch.hx + h.y*latch.hy + h.z*latch.hz) > .999999;
        boolean swapped = Math.abs(portal.getWidth() * .5 - latch.halfH) < 1e-6
            && Math.abs(portal.getHeight() * .5 - latch.halfW) < 1e-6
            && Math.abs(w.x*latch.hx + w.y*latch.hy + w.z*latch.hz) > .999999
            && Math.abs(h.x*latch.wx + h.y*latch.wy + h.z*latch.wz) > .999999;
        return direct || swapped;
    }

    public static boolean touchesPortalVirtually(
        ServerSubLevel airship, @org.jetbrains.annotations.Nullable Portal portal
    ) {
        if (airship == null || portal == null) return false;
        ExitLatch latch = liveExitLatch(airship);
        if (latch == null || !plateIsRectangleOf(latch, portal)) return false;
        return overlapsExit(airship, latch, trail(airship).end);
    }

    public static void pruneTrails() {
        TRAILS.entrySet().removeIf(entry -> {
            if (entry.getValue().lastSeenTick == trailTick) return false;

            EXIT_ANCHORS.remove(entry.getKey());
            EXIT_LATCHES.remove(entry.getKey());
            return true;
        });
    }

    public static void seedPhysicsStart(ServerSubLevel body) {
        TRAILS.computeIfAbsent(body.getUniqueId(), ignored -> new Trail(
            new Pose3d(body.logicalPose()), new Pose3d(body.logicalPose())));
    }

    /** Capture all bodies before evaluating any group; use retained B as the next A. */
    public static void captureTrail(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        Trail trail = TRAILS.get(id);
        if (trail == null) {
            trail = new Trail(new Pose3d(airship.lastPose()),
                new Pose3d(airship.logicalPose()));
            TRAILS.put(id, trail);
        } else {

            trail.seededFromExit = false;
            ExitAnchor anchor = liveAnchor(id);
            if (trail.rebasedThisTick && anchor != null) {

                trail.start.set(anchor.pose);

                trail.rebasedThisTick = false;

                trail.seededFromExit = true;
                EXIT_ANCHORS.remove(id);
            } else if (trail.rebasedThisTick) {

                trail.start.set(trail.end);

                trail.rebasedThisTick = false;
            } else {
                trail.start.set(trail.end);
            }
            trail.end.set(airship.logicalPose());
        }
        trail.evaluations.clear();
        trail.lastSeenTick = trailTick;
    }

    public static void forgetTrail(ServerSubLevel airship) {
        TRAILS.remove(airship.getUniqueId());
        EXIT_LATCHES.remove(airship.getUniqueId());
        EXIT_ANCHORS.remove(airship.getUniqueId());
    }

    public static void resetTrail(ServerSubLevel airship) {

        if (EXIT_ANCHORS.containsKey(airship.getUniqueId())) {
            reseedTrailFromExitAnchor(airship);
            return;
        }
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail == null) {
            captureTrail(airship);
            return;
        }
        if (trail.seededFromExit) {

            return;
        }

        trail.start.set(airship.logicalPose());
        trail.end.set(airship.logicalPose());

        trail.rebasedThisTick = true;
        trail.evaluations.clear();
        trail.lastSeenTick = trailTick;
    }

    @org.jetbrains.annotations.Nullable
    /** Geometry, not elapsed ticks, retires exit contact. History remains continuous. */
    public static UUID settleExit(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        ExitLatch latch = EXIT_LATCHES.get(id);
        if (latch == null) return null;
        if (overlapsExit(airship, latch, trail(airship).end)) return null;
        EXIT_LATCHES.remove(id);

        reseedTrailFromExitAnchor(airship);
        return latch.portalId;
    }

    private static void reseedTrailFromExitAnchor(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        ExitAnchor anchor = liveAnchor(id);
        if (anchor == null) return;
        Trail trail = TRAILS.get(id);
        if (trail == null) {
            trail = new Trail(new Pose3d(anchor.pose),
                new Pose3d(airship.logicalPose()));
            TRAILS.put(id, trail);
        } else {

            trail.start.set(anchor.pose);
            trail.end.set(airship.logicalPose());
        }

        trail.rebasedThisTick = true;
        trail.seededFromExit = true;
        trail.evaluations.clear();
        trail.lastSeenTick = trailTick;
    }

    @org.jetbrains.annotations.Nullable
    private static ExitAnchor liveAnchor(UUID id) {
        return EXIT_ANCHORS.get(id);
    }

    /** Cut at whole-assembly completion, then map A and B through the SAME isometry. */
    public static void rebaseTrailThroughPortal(
        ServerSubLevel airship, Portal portal, double crossingTime
    ) {
        UUID id = airship.getUniqueId();
        Trail trail = TRAILS.get(id);
        Pose3d entry;
        if (trail == null || !Double.isFinite(crossingTime)) {
            entry = new Pose3d(airship.logicalPose());
        } else {

            entry = IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(
                interpolate(trail.start, trail.end, Math.clamp(crossingTime, 0.0, 1.0)));
        }
        if (trail == null) {
            trail = new Trail(new Pose3d(entry), new Pose3d(entry));
            TRAILS.put(id, trail);
        }

        Pose3d anchor = armExitAnchor(airship, portal, entry);

        trail.start.set(anchor);

        armExitClearance(airship, portal);
        trail.end.set(airship.logicalPose());
        trail.rebasedThisTick = true;
        trail.evaluations.clear();
        trail.lastSeenTick = trailTick;
    }

    public static boolean willEnterAperture(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal,
        double apertureMargin, double lookaheadSegments
    ) {
        if (lookaheadSegments <= 0.0) return false;
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(airship);
        if (blocks.isEmpty()) return false;
        Trail trail = trail(airship);
        if (sameBasis(trail.start, trail.end) && trail.start.position().equals(trail.end.position()))
            return false; // No relative body motion can have a directed predictive entry.
        Pose3d ahead = interpolate(trail.start, trail.end, 1.0 + lookaheadSegments);
        SweepResult predicted = sweepSegment(portal, blocks,
            trail.end, ahead, sourceToDestNormal, apertureMargin);
        return predicted.intersects()
            && predicted.direction() == SweepDirection.TOWARD_DESTINATION;
    }

    private static Pose3d interpolate(Pose3dc from, Pose3dc to, double t) {
        Pose3d out = new Pose3d(from);
        out.position().set(
            from.position().x() + (to.position().x() - from.position().x()) * t,
            from.position().y() + (to.position().y() - from.position().y()) * t,
            from.position().z() + (to.position().z() - from.position().z()) * t);
        org.joml.Quaterniond rotation = new org.joml.Quaterniond(from.orientation());
        rotation.slerp(new org.joml.Quaterniond(to.orientation()), Math.clamp(t, 0.0, 1.0));
        out.orientation().set(rotation);
        double bounded = Math.clamp(t, 0.0, 1.0);
        from.rotationPoint().lerp(to.rotationPoint(), bounded, out.rotationPoint());
        from.scale().lerp(to.scale(), bounded, out.scale());
        return out;
    }

    public static Pose3d armExitAnchor(
        ServerSubLevel airship, Portal portal, Pose3dc mappedCrossingPose
    ) {
        Pose3d anchor = projectOntoExitPlane(airship, portal, mappedCrossingPose);
        EXIT_ANCHORS.put(airship.getUniqueId(), new ExitAnchor(
            new Pose3d(anchor), portal == null ? null : portal.getUUID()));
        return anchor;
    }

    /** Compatibility name: deliberately NO normal projection of an actual path pose. */
    public static Pose3d projectOntoExitPlane(
        ServerSubLevel airship, Portal portal, Pose3dc destinationFramePose
    ) {

        return new Pose3d(destinationFramePose);
    }

    public static void armExitClearance(ServerSubLevel airship, Portal portal) {
        if (portal == null) return;

        double clearance = Math.max(EXIT_CLEARANCE_THICKNESS, MIN_EXIT_CLEARANCE);

        IplEnteringVolumeVisualization.clearBuffer(airship);

        IplStraddlePoseMap.StraddleMapping exitFrame =
            IplStraddlePoseMap.StraddleMapping.of(portal);
        Vec3 origin = exitFrame.mapPoint(portal.getOriginPos());
        Vec3 normal = exitFrame.mapVec(portal.getNormal());
        Vec3 axisW = exitFrame.mapVec(portal.getAxisW());
        Vec3 axisH = exitFrame.mapVec(portal.getAxisH());
        EXIT_LATCHES.put(airship.getUniqueId(), new ExitLatch(
            portal.getUUID(),
            origin.x, origin.y, origin.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z,
            axisH.x, axisH.y, axisH.z,
            portal.getWidth() * 0.5,
            portal.getHeight() * 0.5,
            clearance));
    }

    public static boolean withinExitClearance(ServerSubLevel airship, Portal portal) {
        ExitLatch latch = liveExitLatch(airship);
        if (latch == null) return false;

        if (!overlapsExit(airship, latch, trail(airship).end)) return false;

        return plateIsRectangleOf(latch, portal);
    }

    public static void clearTrails() {
        TRAILS.clear();
        EXIT_LATCHES.clear();
        EXIT_ANCHORS.clear();
        trailTick = 0L;
    }

    @org.jetbrains.annotations.Nullable
    public static Pose3dc bufferedExitAnchor(ServerSubLevel airship) {
        ExitAnchor anchor = liveAnchor(airship.getUniqueId());
        return anchor == null ? null : anchor.pose;
    }

    @org.jetbrains.annotations.Nullable
    public static UUID bufferedExitAnchorPortal(ServerSubLevel airship) {
        ExitAnchor anchor = liveAnchor(airship.getUniqueId());
        return anchor == null ? null : anchor.portalId;
    }

    public static boolean hasBufferedExit(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        if (EXIT_ANCHORS.containsKey(id)) return true;

        Trail trail = TRAILS.get(id);
        return trail != null && trail.seededFromExit;
    }

    @org.jetbrains.annotations.Nullable
    public static Pose3dc bufferedPose(ServerSubLevel airship) {
        ExitAnchor anchor = liveAnchor(airship.getUniqueId());
        if (anchor != null) return anchor.pose;
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail == null) return null;

        return trail.seededFromExit ? trail.start : null;
    }

    public static AABB boundsAt(ServerSubLevel airship, double t) {
        Trail trail = trail(airship);
        double clamped = Double.isFinite(t) ? Math.clamp(t, 0.0, 1.0) : 0.0;
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, interpolate(trail.start, trail.end, clamped));
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    public static boolean hasSourceGeometry(ServerSubLevel body, Portal portal) {
        var blocks = IplPortalVolumeCache.blocks(body);
        return !blocks.isEmpty() && sample(blocks, new Frame(trail(body).end),
            portal.getOriginPos(), portal.getNormal().scale(-1.0)).minDistance < -EPSILON;
    }

    /**
     * A prior entry is not permission to finish through an infinite plane. Verify
     * that the LAST source-side geometry actually leaves through the rectangle.
     * If it left sideways while still straddling, this final contact is absent.
     * Already-completed leading joint members are recorded by the session owner.
     */
    public static boolean completesThroughAperture(ServerSubLevel body, Portal portal) {
        return completesThroughAperture(body, portal, 0.0);
    }

    public static boolean completesThroughAperture(ServerSubLevel body, Portal portal, double releaseMargin) {
        Trail trail = trail(body);
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(body);
        if (blocks.isEmpty()) return false;
        Vec3 normal = portal.getNormal().scale(-1.0), origin = portal.getOriginPos();
        if (sample(blocks, new Frame(trail.start), origin, normal).minDistance >= -EPSILON)
            return false;
        if (sample(blocks, new Frame(trail.end), origin, normal).minDistance < 0.0)
            return false;
        double t = completionTime(body, portal, 0.0);
        Frame crossing = new Frame(interpolate(trail.start, trail.end, t));
        return sectionFits(portal, blocks, crossing, releaseMargin, 1.0e-7);
    }

    /** Release when ANY occupied section protrudes too far sideways, even if the
     * rest of the object still overlaps the opening. Distant non-crossing geometry
     * is irrelevant; a sparse plot's empty AABB gap cannot retain the seam either. */
    public static boolean retainsAdmittedSession(ServerSubLevel body, Portal portal) {
        return sectionFits(portal, IplPortalVolumeCache.blocks(body),
            new Frame(trail(body).end), SESSION_RELEASE_MARGIN, PORTAL_HYSTERESIS_DEPTH);
    }

    private static boolean sectionFits(Portal portal, List<BlockPos> blocks, Frame frame,
                                      double margin, double depth) {
        double[][] basis = frame.halfBasis();
        double[] origin = vector(portal.getOriginPos()), w = vector(portal.getAxisW());
        double[] h = vector(portal.getAxisH()), n = vector(portal.getNormal());
        double halfW = portal.getWidth()*.5+margin, halfH = portal.getHeight()*.5+margin;
        boolean touched = false;
        for (BlockPos block : blocks) {
            double[] center = {frame.centerX(block),frame.centerY(block),frame.centerZ(block)};
            int section = PortalSectionMath.classify(center,basis,origin,w,h,n,halfW,halfH,depth);
            if (section == PortalSectionMath.OUTSIDE) return false;
            if (section == PortalSectionMath.INSIDE) touched = true;
        }
        return touched;
    }

    public static double completionTime(ServerSubLevel body, Portal portal) {
        return completionTime(body, portal, PORTAL_HYSTERESIS_DEPTH);
    }

    private static double completionTime(ServerSubLevel body, Portal portal, double depth) {
        Trail trail = trail(body);
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(body);
        if (blocks.isEmpty()) return 1.0;
        Vec3 normal = portal.getNormal().scale(-1.0), origin = portal.getOriginPos();
        // With a fixed affine basis every occupied vertex has the same displacement.
        // The trailing support is linear: two samples replace 40 full block scans.
        if (sameBasis(trail.start, trail.end)) {
            double a = sample(blocks, new Frame(trail.start), origin, normal).minDistance;
            double b = sample(blocks, new Frame(trail.end), origin, normal).minDistance;
            if (b < depth) return 1.0;
            if (a >= depth) return 0.0;
            return Math.clamp((depth - a) / (b - a), 0.0, 1.0);
        }
        int steps = Math.max(1, (int) Math.ceil(rotationAngle(trail.start, trail.end) / MAX_ROTATION_STEP));
        double lastBelow = -1.0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Sample sample = sample(blocks, new Frame(interpolate(trail.start, trail.end, t)), origin, normal);
            if (sample.minDistance < depth) lastBelow = t;
        }
        if (lastBelow < 0.0) return 0.0;
        if (lastBelow >= 1.0) return 1.0;
        double low = lastBelow, high = Math.min(1.0, lastBelow + 1.0 / steps);
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) * 0.5;
            if (sample(blocks, new Frame(interpolate(trail.start, trail.end, mid)), origin, normal)
                .minDistance < depth) low = mid;
            else high = mid;
        }
        return high;
    }

    public static double segmentLength(ServerSubLevel airship) {
        Trail trail = trail(airship);
        return trail.start.position().distance(trail.end.position());
    }

    public static boolean entryWithinExitSlab(ServerSubLevel airship, double entryTime) {

        ExitLatch latch = liveExitLatch(airship);
        if (latch == null) return false;

        if (backwardTravelIntoPlate(airship, latch) <= EPSILON) return false;

        if (Double.isFinite(entryTime) && overlapsExit(airship, latch,
            interpolate(trail(airship).start, trail(airship).end, Math.clamp(entryTime, 0.0, 1.0)))) return true;

        return !Double.isFinite(entryTime) && overlapsExit(airship, latch, trail(airship).end);
    }

    public static AABB currentBounds(ServerSubLevel airship) {
        Trail trail = trail(airship);
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, trail.end);
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    public static AABB sweptBounds(ServerSubLevel airship) {
        Trail trail = trail(airship);
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, trail.start);
        includePlotBounds(bounds, airship, trail.end);

        if (rotationAngle(trail.start, trail.end) > EPSILON) {
            double radius = plotRadius(airship, trail.start, trail.end);
            for (Pose3dc pose : new Pose3dc[]{trail.start, trail.end}) {
                bounds.include(new Vec3(pose.position().x() - radius,
                    pose.position().y() - radius, pose.position().z() - radius));
                bounds.include(new Vec3(pose.position().x() + radius,
                    pose.position().y() + radius, pose.position().z() + radius));
            }
        }
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    private static boolean sameBasis(Pose3dc a, Pose3dc b) {
        return a.orientation().equals(b.orientation())
            && a.rotationPoint().equals(b.rotationPoint()) && a.scale().equals(b.scale());
    }

    private static double rotationAngle(Pose3dc a, Pose3dc b) {
        return 2.0 * Math.acos(Math.clamp(Math.abs(a.orientation().dot(b.orientation())), 0.0, 1.0));
    }

    private static double plotRadius(ServerSubLevel body, Pose3dc a, Pose3dc b) {
        var box = body.getPlot().getBoundingBox();
        double radius = 0.0;
        for (Pose3dc p : new Pose3dc[]{a, b}) {
            for (int i = 0; i < 8; i++) {
                double x = (((i & 1) == 0 ? box.minX() : box.maxX() + 1.0) - p.rotationPoint().x()) * p.scale().x();
                double y = (((i & 2) == 0 ? box.minY() : box.maxY() + 1.0) - p.rotationPoint().y()) * p.scale().y();
                double z = (((i & 4) == 0 ? box.minZ() : box.maxZ() + 1.0) - p.rotationPoint().z()) * p.scale().z();
                radius = Math.max(radius, Math.sqrt(x*x + y*y + z*z));
            }
        }
        return radius;
    }

    public static CrossingState evaluate(ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal) {
        return evaluate(airship, portal, sourceToDestNormal, 0.0);
    }

    public static CrossingState evaluate(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal, double apertureMargin
    ) {
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(airship);
        if (blocks.isEmpty()) {
            return new CrossingState(
                CrossingPhase.APPROACHING, false, false, false,
                SweepDirection.NONE, SweepDirection.NONE, Double.NaN);
        }

        Trail trail = trail(airship);
        EvaluationKey key = new EvaluationKey(portal.getUUID(), portal.getOriginPos(),
            portal.getAxisW(), portal.getAxisH(), sourceToDestNormal,
            portal.getWidth(), portal.getHeight(), apertureMargin, IplPortalVolumeCache.revision(airship));
        CrossingState cached = trail.evaluations.get(key);
        if (cached != null) return cached;
        Frame start = new Frame(trail.start);
        Frame end = new Frame(trail.end);

        Sample current = sample(blocks, end, portal.getOriginPos(), sourceToDestNormal);
        Sample oldest = sample(blocks, start, portal.getOriginPos(), sourceToDestNormal);
        boolean staticPose = sameBasis(trail.start, trail.end)
            && trail.start.position().equals(trail.end.position());
        boolean currentContact = current.minDistance <= PORTAL_HYSTERESIS_DEPTH + EPSILON
            && current.maxDistance >= -PORTAL_HYSTERESIS_DEPTH - EPSILON
            && overlapsAperture(portal, blocks, end, sourceToDestNormal, apertureMargin);
        SweepResult recent = staticPose ? new SweepResult(currentContact
            && current.minDistance <= EPSILON && current.maxDistance >= -EPSILON
            && overlapsAperture(portal, blocks, end, sourceToDestNormal, apertureMargin, 0.0),
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY) : sweepSegment(
                portal, blocks, trail.start, trail.end, sourceToDestNormal, apertureMargin);
        SweepResult sweep = recent;

        CrossingState result = new CrossingState(
            phase(current.minDistance, current.maxDistance),

            oldest.minDistance < -EPSILON,
            sweep.intersects,
            currentContact,
            sweep.direction(),
            staticPose ? SweepDirection.NONE : motionDirection(blocks, start, end, sourceToDestNormal),

            Double.isFinite(recent.towardTime()) ? recent.towardTime() : Double.NaN);
        trail.evaluations.put(key, result);
        IplEnteringVolumeVisualization.record(airship, portal, result);
        return result;
    }

    private static SweepDirection motionDirection(List<BlockPos> blocks, Frame from, Frame to, Vec3 normal) {
        double movement = 0.0;
        for (BlockPos b : blocks) {
            movement += (to.centerX(b) - from.centerX(b)) * normal.x
                + (to.centerY(b) - from.centerY(b)) * normal.y
                + (to.centerZ(b) - from.centerZ(b)) * normal.z;
        }
        return movement > EPSILON ? SweepDirection.TOWARD_DESTINATION
            : movement < -EPSILON ? SweepDirection.TOWARD_SOURCE : SweepDirection.NONE;
    }

    private static Trail trail(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail != null) return trail;
        return new Trail(new Pose3d(airship.lastPose()),
            new Pose3d(airship.logicalPose()));
    }

    private static void includePlotBounds(Bounds out, ServerSubLevel airship, Pose3dc pose) {
        var bounds = airship.getPlot().getBoundingBox();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            out.include(pose.transformPosition(new Vec3(
                x == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                y == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0)));
        }
    }

    private static CrossingPhase phase(double min, double max) {
        if (min >= PORTAL_HYSTERESIS_DEPTH) return CrossingPhase.CROSSED;
        return max >= -PORTAL_HYSTERESIS_DEPTH ? CrossingPhase.STRADDLING : CrossingPhase.APPROACHING;
    }

    private static Sample sample(List<BlockPos> blocks, Frame frame, Vec3 plane, Vec3 normal) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double support = frame.obbSupport(normal.x, normal.y, normal.z);
        for (BlockPos block : blocks) {
            double distance = (frame.centerX(block) - plane.x) * normal.x
                + (frame.centerY(block) - plane.y) * normal.y
                + (frame.centerZ(block) - plane.z) * normal.z;
            min = Math.min(min, distance - support);
            max = Math.max(max, distance + support);
        }
        return new Sample(min, max);
    }

    /** SAT is exact for translation; <=5 degree slices bound rotational chord error. */
    private static SweepResult sweepSegment(
        Portal portal, List<BlockPos> blocks, Pose3dc fromPose, Pose3dc toPose,
        Vec3 normal, double margin
    ) {
        double angle = rotationAngle(fromPose, toPose);
        int steps = Math.max(1, (int) Math.ceil(angle / MAX_ROTATION_STEP));
        boolean intersects = false;
        double toward = Double.POSITIVE_INFINITY, source = Double.POSITIVE_INFINITY;
        double[] origin = vector(portal.getOriginPos());
        double[] w = vector(portal.getAxisW()), h = vector(portal.getAxisH()), n = vector(normal);
        Frame initial = new Frame(fromPose), terminal = new Frame(toPose);
        double initialS = initial.obbSupport(n[0], n[1], n[2]);
        double finalS = terminal.obbSupport(n[0], n[1], n[2]);
        double pivotChange = fromPose.rotationPoint().distance(toPose.rotationPoint());
        double scaleChange = fromPose.scale().distance(toPose.scale());
        double maxScale = Math.max(fromPose.scale().length(), toPose.scale().length());
        Frame from = initial;
        for (int step = 0; step < steps; step++) {
            Pose3d nextPose = interpolate(fromPose, toPose, (step + 1.0) / steps);
            Frame to = new Frame(nextPose);
            double[][] ba = from.halfBasis(), bb = to.halfBasis();
            double sa = PortalSweepMath.support(ba, n), sb = PortalSweepMath.support(bb, n);
            for (BlockPos block : blocks) {
                double[] a = {from.centerX(block), from.centerY(block), from.centerZ(block)};
                double[] b = {to.centerX(block), to.centerY(block), to.centerZ(block)};

                double curvature = angle > EPSILON
                    ? blockRadius(block, fromPose, toPose) * (1.0 - Math.cos(angle / steps * 0.5)) : 0.0;

                if (pivotChange > EPSILON || scaleChange > EPSILON) {
                    double localRadius = Math.max(
                        fromPose.rotationPoint().distance(block.getX()+.5, block.getY()+.5, block.getZ()+.5),
                        toPose.rotationPoint().distance(block.getX()+.5, block.getY()+.5, block.getZ()+.5)) + 1.0;
                    // Bound affine deformation and its rotational cross term. This is
                    // deliberately conservative; rigid fixed-scale paths incur no pad.
                    curvature += (pivotChange * maxScale + scaleChange * localRadius) / steps;
                }
                PortalSweepMath.Hit hit = PortalSweepMath.sweep(a, b, ba, bb, origin, w, h, n,
                    portal.getWidth() * 0.5 + margin, portal.getHeight() * 0.5 + margin, 0.0, curvature);
                if (hit == null) continue;
                intersects = true;
                double da = (a[0]-origin[0])*n[0] + (a[1]-origin[1])*n[1] + (a[2]-origin[2])*n[2];
                double db = (b[0]-origin[0])*n[0] + (b[1]-origin[1])*n[1] + (b[2]-origin[2])*n[2];
                double time = (step + hit.enter()) / steps;
                double initialD = (initial.centerX(block)-origin[0])*n[0]
                    + (initial.centerY(block)-origin[1])*n[1] + (initial.centerZ(block)-origin[2])*n[2];
                double finalD = (terminal.centerX(block)-origin[0])*n[0]
                    + (terminal.centerY(block)-origin[1])*n[1] + (terminal.centerZ(block)-origin[2])*n[2];
                if ((da + sa < -EPSILON && db + sb >= -EPSILON)
                    || (initialD + initialS < -EPSILON && finalD + finalS >= -EPSILON))
                    toward = Math.min(toward, time);
                if ((da - sa > EPSILON && db - sb <= EPSILON)
                    || (initialD - initialS > EPSILON && finalD - finalS <= EPSILON))
                    source = Math.min(source, time);
            }
            from = to;
        }
        return new SweepResult(intersects, toward, source);
    }

    private static double[] vector(Vec3 v) { return new double[]{v.x, v.y, v.z}; }

    private static double blockRadius(BlockPos block, Pose3dc a, Pose3dc b) {
        double radius = 0.0;
        for (Pose3dc pose : new Pose3dc[]{a, b}) {
            double x = (Math.abs(block.getX() + 0.5 - pose.rotationPoint().x()) + 0.5) * Math.abs(pose.scale().x());
            double y = (Math.abs(block.getY() + 0.5 - pose.rotationPoint().y()) + 0.5) * Math.abs(pose.scale().y());
            double z = (Math.abs(block.getZ() + 0.5 - pose.rotationPoint().z()) + 0.5) * Math.abs(pose.scale().z());
            radius = Math.max(radius, Math.sqrt(x*x + y*y + z*z));
        }
        return radius;
    }

    private static boolean overlapsExit(ServerSubLevel body, ExitLatch latch, Pose3dc pose) {
        Frame frame = new Frame(pose);
        double[][] basis = frame.halfBasis();
        double[] origin = {latch.ox, latch.oy, latch.oz};
        double[] w = {latch.wx, latch.wy, latch.wz}, h = {latch.hx, latch.hy, latch.hz};
        double[] n = {latch.nx, latch.ny, latch.nz};
        for (BlockPos block : IplPortalVolumeCache.blocks(body)) {
            double[] center = {frame.centerX(block), frame.centerY(block), frame.centerZ(block)};
            if (PortalSweepMath.sweep(center, center, basis, basis, origin, w, h, n,
                latch.halfW, latch.halfH, latch.halfThickness, 0.0) != null) return true;
        }
        return false;
    }

    private static boolean overlapsAperture(
        Portal portal, List<BlockPos> blocks, Frame frame, Vec3 normal, double margin
    ) {
        return overlapsAperture(portal, blocks, frame, normal, margin, PORTAL_HYSTERESIS_DEPTH);
    }

    private static boolean overlapsAperture(
        Portal portal, List<BlockPos> blocks, Frame frame, Vec3 normal, double margin, double thickness
    ) {
        double[][] basis = frame.halfBasis();
        double[] origin = vector(portal.getOriginPos());
        double[] w = vector(portal.getAxisW()), h = vector(portal.getAxisH()), n = vector(normal);
        for (BlockPos block : blocks) {
            double[] center = {frame.centerX(block), frame.centerY(block), frame.centerZ(block)};
            if (PortalSweepMath.sweep(center, center, basis, basis, origin, w, h, n,
                portal.getWidth() * .5 + margin, portal.getHeight() * .5 + margin,
                thickness, 0.0) != null) return true;
        }
        return false;
    }

    private static final class ExitAnchor {
        final Pose3d pose;
        @org.jetbrains.annotations.Nullable final UUID portalId;

        ExitAnchor(Pose3d pose, @org.jetbrains.annotations.Nullable UUID portalId) {
            this.pose = pose;
            this.portalId = portalId;
        }
    }

    private static final class ExitLatch {
        final UUID portalId;
        final double ox, oy, oz;
        final double nx, ny, nz;
        final double wx, wy, wz;
        final double hx, hy, hz;
        final double halfW, halfH, halfThickness;

        ExitLatch(UUID portalId,
                  double ox, double oy, double oz,
                  double nx, double ny, double nz,
                  double wx, double wy, double wz,
                  double hx, double hy, double hz,
                  double halfW, double halfH, double halfThickness) {
            this.portalId = portalId;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.wx = wx; this.wy = wy; this.wz = wz;
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.halfW = halfW;
            this.halfH = halfH;
            this.halfThickness = halfThickness;
        }

    }

    private record EvaluationKey(UUID portal, Vec3 origin, Vec3 axisW, Vec3 axisH,
        Vec3 normal, double width, double height, double margin, long blockRevision) {}

    private static final class Trail {
        final Map<EvaluationKey, CrossingState> evaluations = new HashMap<>();
        final Pose3d start;
        final Pose3d end;
        boolean rebasedThisTick;
        boolean seededFromExit;
        long lastSeenTick;

        Trail(Pose3d start, Pose3d end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Frame {
        final double ox, oy, oz;
        final double xx, xy, xz, yx, yy, yz, zx, zy, zz;

        final double pivotX, pivotY, pivotZ;

        Frame(Pose3dc pose) {
            // Keep plot-local coordinates relative to the rotation point. Forming
            // transform(0) + R*globalPlotBlock subtracts two huge numbers at distant
            // plot slots and needlessly loses precision at an oblique portal plane.
            var x = pose.orientation().transform(new org.joml.Vector3d(pose.scale().x(), 0, 0));
            var y = pose.orientation().transform(new org.joml.Vector3d(0, pose.scale().y(), 0));
            var z = pose.orientation().transform(new org.joml.Vector3d(0, 0, pose.scale().z()));
            ox = pose.position().x(); oy = pose.position().y(); oz = pose.position().z();
            pivotX = pose.rotationPoint().x(); pivotY = pose.rotationPoint().y(); pivotZ = pose.rotationPoint().z();
            xx = x.x; xy = x.y; xz = x.z;
            yx = y.x; yy = y.y; yz = y.z;
            zx = z.x; zy = z.y; zz = z.z;
        }

        double[][] halfBasis() {
            return new double[][]{{xx * .5, xy * .5, xz * .5},
                {yx * .5, yy * .5, yz * .5}, {zx * .5, zy * .5, zz * .5}};
        }

        double centerX(BlockPos block) {
            double x = block.getX() + 0.5 - pivotX, y = block.getY() + 0.5 - pivotY, z = block.getZ() + 0.5 - pivotZ;
            return ox + xx * x + yx * y + zx * z;
        }

        double centerY(BlockPos block) {
            double x = block.getX() + 0.5 - pivotX, y = block.getY() + 0.5 - pivotY, z = block.getZ() + 0.5 - pivotZ;
            return oy + xy * x + yy * y + zy * z;
        }

        double centerZ(BlockPos block) {
            double x = block.getX() + 0.5 - pivotX, y = block.getY() + 0.5 - pivotY, z = block.getZ() + 0.5 - pivotZ;
            return oz + xz * x + yz * y + zz * z;
        }

        double obbSupport(double x, double y, double z) {
            return (Math.abs(xx * x + xy * y + xz * z)
                + Math.abs(yx * x + yy * y + yz * z)
                + Math.abs(zx * x + zy * y + zz * z)) * 0.5;
        }

    }

    private record Sample(double minDistance, double maxDistance) {}

    private record SweepResult(boolean intersects, double towardTime, double sourceTime) {
        static final SweepResult NONE = new SweepResult(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

        SweepDirection direction() {
            boolean toward = Double.isFinite(towardTime);
            boolean source = Double.isFinite(sourceTime);
            if (!toward && !source) return SweepDirection.NONE;
            if (toward && !source) return SweepDirection.TOWARD_DESTINATION;
            if (!toward) return SweepDirection.TOWARD_SOURCE;
            if (towardTime + EPSILON < sourceTime) return SweepDirection.TOWARD_DESTINATION;
            if (sourceTime + EPSILON < towardTime) return SweepDirection.TOWARD_SOURCE;
            return SweepDirection.AMBIGUOUS;
        }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        void include(Vec3 point) {
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
        }
    }
}
