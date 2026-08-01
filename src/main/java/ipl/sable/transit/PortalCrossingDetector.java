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

/**
 * History-backed finite-aperture swept-AABB detector.
 *
 * <p>For every occupied cube, each physics segment sweeps its endpoint AABB center through
 * a portal aperture expanded by that AABB's half-extents (Minkowski sum). Plane and aperture
 * are clipped as one continuous segment test, not tick samples or rays. Two adjacent physics
 * segments are retained: a portal skipped between one pair of visible poses remains eligible
 * when its final pose is inspected on the following tick.
 */
public final class PortalCrossingDetector {

    private static final double EPSILON = 1.0e-8;
    /** Half-depth of portal entry/exit hysteresis; not part of face direction. */
    private static final double PORTAL_HYSTERESIS_DEPTH = 0.1;
    private static final Map<UUID, Trail> TRAILS = new HashMap<>();
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
        SweepDirection currentSweepDirection
    ) {
        public boolean sweptTowardDestination() {
            return sweptEntryDirection == SweepDirection.TOWARD_DESTINATION;
        }
    }

    /** Starts one hosting-container sweep; stale trail entries are pruned at its end. */
    public static void beginTrailTick() {
        trailTick++;
    }

    /** Removes trails for ships no longer visited by the hosting container. */
    public static void pruneTrails() {
        TRAILS.entrySet().removeIf(entry -> entry.getValue().lastSeenTick != trailTick);
    }

    /** Capture once before broad-phase query. Retains a continuous two-tick pose trail. */
    public static void captureTrail(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        Trail trail = TRAILS.get(id);
        if (trail == null) {
            trail = new Trail(new Pose3d(airship.lastPose()), new Pose3d(airship.lastPose()),
                new Pose3d(airship.logicalPose()));
            TRAILS.put(id, trail);
        } else {
            // Reuse all pose objects. The old implementation allocated three poses and
            // a record for every hosted ship every server tick.
            trail.older.set(trail.start);
            trail.hasOlder = true;
            trail.start.set(airship.lastPose());
            trail.end.set(airship.logicalPose());
        }
        trail.lastSeenTick = trailTick;
    }

    /** Forget source-frame history after a parent flip; it cannot be joined to destination poses. */
    public static void forgetTrail(ServerSubLevel airship) {
        TRAILS.remove(airship.getUniqueId());
    }

    /**
     * A completed source-side exit starts a fresh trail at its current pose. This discards
     * the just-unwound portal segment, so it cannot claim the coincident back face next tick.
     */
    public static void resetTrail(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail == null) {
            captureTrail(airship);
            return;
        }
        trail.older.set(airship.logicalPose());
        trail.start.set(airship.logicalPose());
        trail.end.set(airship.logicalPose());
        trail.hasOlder = false;
        trail.lastSeenTick = trailTick;
    }

    public static void clearTrails() {
        TRAILS.clear();
        trailTick = 0L;
    }

    /** Debug-only prior endpoint. Null until the current ship has one completed segment. */
    public static Pose3dc bufferedPose(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        return trail != null && trail.hasOlder ? trail.older : null;
    }

    /** Broad phase covers both retained swept segments. */
    public static AABB sweptBounds(ServerSubLevel airship) {
        Trail trail = trail(airship);
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, trail.start);
        includePlotBounds(bounds, airship, trail.end);
        if (trail.hasOlder) includePlotBounds(bounds, airship, trail.older);
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
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
                SweepDirection.NONE, SweepDirection.NONE);
        }

        Trail trail = trail(airship);
        Frame start = new Frame(trail.start);
        Frame end = new Frame(trail.end);
        Frame older = trail.hasOlder ? new Frame(trail.older) : null;
        Sample current = sample(blocks, end, portal.getOriginPos(), sourceToDestNormal);
        Sample oldest = sample(blocks, older == null ? start : older, portal.getOriginPos(), sourceToDestNormal);
        SweepResult recent = sweepSegment(
            portal, blocks, start, end, sourceToDestNormal, apertureMargin);
        SweepResult historic = older == null ? SweepResult.NONE : sweepSegment(
            portal, blocks, older, start, sourceToDestNormal, apertureMargin);
        SweepResult sweep = recent.mergePreferred(historic);

        CrossingState result = new CrossingState(
            phase(current.minDistance, current.maxDistance),
            // This is entry evidence, not phase hysteresis. Requiring the body to be an
            // extra band behind the plane loses slow crossings whose leading block first
            // touches it within that band.
            oldest.minDistance < -EPSILON,
            sweep.intersects,
            overlapsAperture(portal, blocks, end, sourceToDestNormal, apertureMargin),
            sweep.direction(),
            recent.direction());
        IplEnteringVolumeVisualization.record(airship, portal, result);
        return result;
    }

    private static Trail trail(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail != null) return trail;
        return new Trail(new Pose3d(airship.lastPose()), new Pose3d(airship.lastPose()),
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

    /**
     * Exact endpoint OBB projection using the cached pose basis, with zero per-block allocations.
     *
     * <p>Phase is physical block geometry. Swept aperture admission below deliberately
     * uses a conservative endpoint AABB, so very fast motion cannot tunnel past a finite
     * portal. These tests answer different questions and must not be conflated.
     */
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

    /** Swept AABB vs finite portal plane. `from`/`to` can be arbitrarily far apart. */
    private static SweepResult sweepSegment(
        Portal portal, List<BlockPos> blocks, Frame from, Frame to, Vec3 normal, double margin
    ) {
        boolean intersects = false;
        double towardTime = Double.POSITIVE_INFINITY;
        double sourceTime = Double.POSITIVE_INFINITY;
        Vec3 origin = portal.getOriginPos();
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();
        double fromPlaneSupport = from.aabbSupport(normal.x, normal.y, normal.z);
        double toPlaneSupport = to.aabbSupport(normal.x, normal.y, normal.z);
        // Keep the endpoint AABB for conservative swept-aperture admission, but never
        // use it to choose a portal face. Its projection is wider than the physical OBB
        // on an inclined plane, which can hide a genuine source-side entry entirely.
        double fromPhysicalPlaneSupport = from.obbSupport(normal.x, normal.y, normal.z);
        double toPhysicalPlaneSupport = to.obbSupport(normal.x, normal.y, normal.z);
        double planeHalf = Math.max(fromPlaneSupport, toPlaneSupport);
        double halfW = portal.getWidth() * 0.5
            + Math.max(from.aabbSupport(axisW.x, axisW.y, axisW.z), to.aabbSupport(axisW.x, axisW.y, axisW.z)) + margin;
        double halfH = portal.getHeight() * 0.5
            + Math.max(from.aabbSupport(axisH.x, axisH.y, axisH.z), to.aabbSupport(axisH.x, axisH.y, axisH.z)) + margin;
        for (BlockPos block : blocks) {
            double fromX = from.centerX(block), fromY = from.centerY(block), fromZ = from.centerZ(block);
            double toX = to.centerX(block), toY = to.centerY(block), toZ = to.centerZ(block);
            double fromPlane = (fromX - origin.x) * normal.x + (fromY - origin.y) * normal.y + (fromZ - origin.z) * normal.z;
            double toPlane = (toX - origin.x) * normal.x + (toY - origin.y) * normal.y + (toZ - origin.z) * normal.z;
            double deltaPlane = toPlane - fromPlane;
            double start;
            double end;
            if (Math.abs(deltaPlane) <= EPSILON) {
                if (Math.abs(fromPlane) > planeHalf + PORTAL_HYSTERESIS_DEPTH + EPSILON) continue;
                start = 0.0;
                end = 1.0;
            } else {
                double a = (-planeHalf - PORTAL_HYSTERESIS_DEPTH - fromPlane) / deltaPlane;
                double b = (planeHalf + PORTAL_HYSTERESIS_DEPTH - fromPlane) / deltaPlane;
                start = Math.max(0.0, Math.min(a, b));
                end = Math.min(1.0, Math.max(a, b));
                if (start > end + EPSILON) continue;
            }
            if (!segmentIntersectsAperture(
                origin, axisW, axisH, fromX, fromY, fromZ, toX, toY, toZ, start, end, halfW, halfH)) continue;

            intersects = true;
            // Face selection follows the block's own continuous A-to-B trajectory.
            // No position threshold or seam fallback: the opposite face receives the
            // same hit with the opposite signed motion and is rejected by controller.
            // Direction belongs to a real volume-boundary crossing only. A block that
            // already straddles the plane may drift or jitter in either signed direction;
            // that must retain an existing session, never choose a new portal face.
            boolean enteredDestination = fromPlane + fromPhysicalPlaneSupport < -EPSILON
                && toPlane + toPhysicalPlaneSupport >= -EPSILON;
            boolean enteredSource = fromPlane - fromPhysicalPlaneSupport > EPSILON
                && toPlane - toPhysicalPlaneSupport <= EPSILON;
            if (enteredDestination) {
                towardTime = Math.min(towardTime, start);
            } else if (enteredSource) {
                sourceTime = Math.min(sourceTime, start);
            }
        }
        return new SweepResult(intersects, towardTime, sourceTime);
    }

    /** Current finite-aperture overlap for an already-owned straddle session. */
    private static boolean overlapsAperture(
        Portal portal, List<BlockPos> blocks, Frame frame, Vec3 normal, double margin
    ) {
        Vec3 origin = portal.getOriginPos();
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();
        double halfW = portal.getWidth() * 0.5
            + margin;
        double halfH = portal.getHeight() * 0.5
            + margin;
        for (BlockPos block : blocks) {
            if (frame.intersectsAperture(block, origin, normal, axisW, axisH, halfW, halfH)) {
                return true;
            }
        }
        return false;
    }

    /** Liang-Barsky segment clipping against width/height aperture expanded by the swept AABB. */
    private static boolean segmentIntersectsAperture(
        Vec3 origin, Vec3 axisW, Vec3 axisH,
        double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
        double planeStart, double planeEnd, double halfW, double halfH
    ) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double startX = fromX + dx * planeStart - origin.x;
        double startY = fromY + dy * planeStart - origin.y;
        double startZ = fromZ + dz * planeStart - origin.z;
        double endX = fromX + dx * planeEnd - origin.x;
        double endY = fromY + dy * planeEnd - origin.y;
        double endZ = fromZ + dz * planeEnd - origin.z;
        double startW = startX * axisW.x + startY * axisW.y + startZ * axisW.z;
        double startH = startX * axisH.x + startY * axisH.y + startZ * axisH.z;
        double deltaW = (endX - startX) * axisW.x + (endY - startY) * axisW.y + (endZ - startZ) * axisW.z;
        double deltaH = (endX - startX) * axisH.x + (endY - startY) * axisH.y + (endZ - startZ) * axisH.z;
        double lower = 0.0;
        double upper = 1.0;
        if (Math.abs(deltaW) <= EPSILON) {
            if (startW < -halfW - EPSILON || startW > halfW + EPSILON) return false;
        } else {
            double a = (-halfW - startW) / deltaW;
            double b = (halfW - startW) / deltaW;
            lower = Math.max(lower, Math.min(a, b));
            upper = Math.min(upper, Math.max(a, b));
            if (lower > upper + EPSILON) return false;
        }
        if (Math.abs(deltaH) <= EPSILON) {
            return startH >= -halfH - EPSILON && startH <= halfH + EPSILON;
        }
        double a = (-halfH - startH) / deltaH;
        double b = (halfH - startH) / deltaH;
        lower = Math.max(lower, Math.min(a, b));
        upper = Math.min(upper, Math.max(a, b));
        return lower <= upper + EPSILON;
    }

    private static final class Trail {
        final Pose3d older;
        final Pose3d start;
        final Pose3d end;
        boolean hasOlder;
        long lastSeenTick;

        Trail(Pose3d older, Pose3d start, Pose3d end) {
            this.older = older;
            this.start = start;
            this.end = end;
        }
    }

    /** Transform basis and endpoint world-AABB support, shared by every block in one pose. */
    private static final class Frame {
        final double ox, oy, oz;
        final double xx, xy, xz, yx, yy, yz, zx, zy, zz;
        final double halfX, halfY, halfZ;

        Frame(Pose3dc pose) {
            Vec3 origin = pose.transformPosition(Vec3.ZERO);
            Vec3 x = pose.transformPosition(new Vec3(1.0, 0.0, 0.0)).subtract(origin);
            Vec3 y = pose.transformPosition(new Vec3(0.0, 1.0, 0.0)).subtract(origin);
            Vec3 z = pose.transformPosition(new Vec3(0.0, 0.0, 1.0)).subtract(origin);
            ox = origin.x; oy = origin.y; oz = origin.z;
            xx = x.x; xy = x.y; xz = x.z;
            yx = y.x; yy = y.y; yz = y.z;
            zx = z.x; zy = z.y; zz = z.z;
            halfX = (Math.abs(xx) + Math.abs(yx) + Math.abs(zx)) * 0.5;
            halfY = (Math.abs(xy) + Math.abs(yy) + Math.abs(zy)) * 0.5;
            halfZ = (Math.abs(xz) + Math.abs(yz) + Math.abs(zz)) * 0.5;
        }

        double centerX(BlockPos block) {
            double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;
            return ox + xx * x + yx * y + zx * z;
        }

        double centerY(BlockPos block) {
            double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;
            return oy + xy * x + yy * y + zy * z;
        }

        double centerZ(BlockPos block) {
            double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;
            return oz + xz * x + yz * y + zz * z;
        }

        double aabbSupport(double x, double y, double z) {
            return halfX * Math.abs(x) + halfY * Math.abs(y) + halfZ * Math.abs(z);
        }

        double obbSupport(double x, double y, double z) {
            return (Math.abs(xx * x + xy * y + xz * z)
                + Math.abs(yx * x + yy * y + yz * z)
                + Math.abs(zx * x + zy * y + zz * z)) * 0.5;
        }

        /** Exact SAT test between one transformed block OBB and the finite portal rectangle. */
        boolean intersectsAperture(
            BlockPos block, Vec3 origin, Vec3 normal, Vec3 axisW, Vec3 axisH,
            double halfW, double halfH
        ) {
            double centerX = centerX(block), centerY = centerY(block), centerZ = centerZ(block);
            return !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, axisW.x, axisW.y, axisW.z)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, axisH.x, axisH.y, axisH.z)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, normal.x, normal.y, normal.z)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz, axisW)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz, axisH)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz, normal)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz, axisW)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz, axisH)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz, normal)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz, axisW)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz, axisH)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz, normal);
        }

        private boolean crossSeparates(
            double centerX, double centerY, double centerZ, Vec3 origin, Vec3 axisW, Vec3 axisH,
            double halfW, double halfH, double ax, double ay, double az, Vec3 b
        ) {
            double crossX = ay * b.z - az * b.y;
            double crossY = az * b.x - ax * b.z;
            double crossZ = ax * b.y - ay * b.x;
            return crossX * crossX + crossY * crossY + crossZ * crossZ > EPSILON * EPSILON
                && separatesAperture(
                    centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH,
                    crossX, crossY, crossZ);
        }

        private boolean separatesAperture(
            double centerX, double centerY, double centerZ, Vec3 origin, Vec3 axisW, Vec3 axisH,
            double halfW, double halfH, double axisX, double axisY, double axisZ
        ) {
            double relX = centerX - origin.x, relY = centerY - origin.y, relZ = centerZ - origin.z;
            double centerDistance = Math.abs(relX * axisX + relY * axisY + relZ * axisZ);
            double blockRadius = obbSupport(axisX, axisY, axisZ);
            double rectangleRadius = halfW * Math.abs(axisW.x * axisX + axisW.y * axisY + axisW.z * axisZ)
                + halfH * Math.abs(axisH.x * axisX + axisH.y * axisY + axisH.z * axisZ);
            return centerDistance > blockRadius + rectangleRadius + EPSILON;
        }
    }

    private record Sample(double minDistance, double maxDistance) {}

    private record SweepResult(boolean intersects, double towardTime, double sourceTime) {
        static final SweepResult NONE = new SweepResult(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

        /** Current segment has priority; history only fills a stationary/absent current trail. */
        SweepResult mergePreferred(SweepResult historic) {
            if (direction() != SweepDirection.NONE) return this;
            if (historic.direction() != SweepDirection.NONE) {
                return new SweepResult(intersects || historic.intersects, historic.towardTime, historic.sourceTime);
            }
            return new SweepResult(intersects || historic.intersects, towardTime, sourceTime);
        }

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
