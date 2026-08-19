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
    /**
     * EXIT HYSTERESIS. A transit that has just executed leaves the body sitting a hair
     * past the portal plane in the destination frame. At absurd speeds the very next
     * evaluation can read that pose as "already left the aperture" (tearing the seam down
     * one tick after building it) or, worse, as a fresh crossing through the coincident
     * BACK face -- the body bounces back and forth through the same doorway instead of
     * flying out of it.
     *
     * <p>The fix is the user-described pair: at the moment of the crossing we snapshot the
     * body's position, and until it has travelled this fraction of the portal's own width
     * away from that snapshot the exit simply does not count. The seam is retained and no
     * new crossing may be admitted, so the back face can never claim the body.
     *
     * <p>The band is deliberately tiny (1% of the portal's width): a body actually flying
     * away clears it within the same physics segment, so legitimate fast chains through a
     * portal loop are untouched.
     */
    private static final double EXIT_CLEARANCE_THICKNESS = 0.01;
    /** Floor for degenerate/hairline portals, so the band is never numerically zero. */
    private static final double MIN_EXIT_CLEARANCE = 1.0e-3;
    /**
     * EXIT ANCHOR ("fake point A").
     *
     * <p>A rehome does not move the body to the exit rectangle -- it maps the WHOLE physics
     * segment through the portal, so the body legitimately materialises further out than the
     * exit frame (the client sees that distance as interpolation, and it is correct: the
     * construction really was there, ticks simply cannot show it). At high speed "further
     * out" can be past the NEXT portal, and then the following tick has a segment that starts
     * where the body already is -- BEHIND that portal -- so the A-to-B sweep has nothing to
     * intersect and the doorway is missed.
     *
     * <p>The anchor is the missing piece of history: at the instant of the flip the exit pose
     * is buffered and used as point A of the NEXT segment. Nothing special-cases the second
     * portal after that; the ordinary A-to-B sweep runs from the exit aperture to the body's
     * real pose and crosses that portal's aperture exactly like any other segment.
     *
     * <p>Offset along the portal's own exit direction only (never the plane edges), matching
     * the session-hold band, so the anchor can never sit a hair BEHIND the exit face and read
     * as an approach through the coincident back face.
     */
    private static final double EXIT_ANCHOR_OFFSET = 0.01;
    /**
     * The anchor has NO tick budget, deliberately. Its whole life is: armed by a flip,
     * consumed by the very next capture, replaced by the next flip, dropped with its trail.
     * A tick-delta expiry is a way to LOSE the buffer in between, and losing it costs a
     * whole doorway -- the next segment then starts where the body already is, which is the
     * exact state the anchor exists to prevent. Counting ticks here also silently assumed
     * the counter advances once per game tick, which is not something this class can know.
     */
    private static final Map<UUID, ExitAnchor> EXIT_ANCHORS = new HashMap<>();
    /**
     * Hard safety ceiling on the exit plate, NOT its normal release mechanism.
     *
     * <p>This used to be 3 ticks, and that was the hole the reverse entries came through.
     * The plate is the only thing covering the exit rectangle; three ticks after the exit
     * it vanished, and a body still jittering next to the doorway was free to re-enter it
     * from the far side. Three ticks is also far shorter than a human can mash a control.
     *
     * <p>THIS BUDGET IS NOT THE PROTECTION MECHANISM, and it must stay small. Raising it to
     * 40 multiplied the cost of every misfire by thirteen and cut loop survival to half the
     * baseline -- the "two ticks per doorway" signature documented in SableTransitController's
     * exit-hysteresis branch. Tick counts are the wrong tool for this problem entirely: a
     * player can jitter a body across the plane in fewer ticks than any budget can span.
     *
     * <p>The real protection is geometric and has no clock in it: the doorway carries a real
     * 0.01 thickness for exactly as long as the body is inside it, and every classification
     * is carried by the trail's past point (see {@link #rebaseTrailThroughPortal}). This budget only
     * bounds the pathological case of a body parked permanently inside the band, so that a
     * latch can never be immortal.
     *
     * <p>Normal release is geometric, in {@link #settleExit}, the moment the body's current
     * pose no longer touches the plate.
     */
    private static final long EXIT_LATCH_MAX_TICKS = 3L;
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
        /** Fraction of the CURRENT segment at which the source->destination entry happened. */
        double entryTime
    ) {
        public boolean sweptTowardDestination() {
            return sweptEntryDirection == SweepDirection.TOWARD_DESTINATION;
        }
    }

    /** Starts one hosting-container sweep; stale trail entries are pruned at its end. */
    public static void beginTrailTick() {
        trailTick++;
        // Latch expiry lives HERE, once per tick, and nowhere else.
        //
        // It used to live inside the predicates, which was the defect: the transit scan
        // asks about every candidate portal in the same tick -- and about each portal's
        // coincident twin -- so a predicate that clears the latch on its own negative
        // answer destroys the plate for every portal examined AFTER the first one that
        // happens not to overlap it. The plate meant to guard the doorway the body just
        // left was routinely gone before that doorway was even reached in the scan.
        EXIT_LATCHES.values().removeIf(latch -> trailTick - latch.tick > EXIT_LATCH_MAX_TICKS);
    }

    /**
     * The body's exit plate if it is still within its safety budget, else null.
     *
     * <p>Read-only by contract: this is called from predicates that run several times per
     * tick, and mutating the latch map from any of them reintroduces the scan-order hole
     * described in {@link #beginTrailTick}. Only {@link #settleExit} and
     * {@link #beginTrailTick} may remove a latch.
     */
    private static ExitLatch liveExitLatch(ServerSubLevel airship) {
        if (airship == null) return null;
        ExitLatch latch = EXIT_LATCHES.get(airship.getUniqueId());
        if (latch == null) return null;
        if (trailTick - latch.tick > EXIT_LATCH_MAX_TICKS) return null;
        return latch;
    }

    /**
     * Signed travel of the body's centre across the retained segment, projected on the
     * plate normal. Positive means the body is heading BACK toward the rectangle it left.
     */
    private static double backwardTravelIntoPlate(ServerSubLevel airship, ExitLatch latch) {
        AABB start = boundsAt(airship, 0.0);
        AABB end = currentBounds(airship);
        double tx = (end.minX + end.maxX) * 0.5 - (start.minX + start.maxX) * 0.5;
        double ty = (end.minY + end.maxY) * 0.5 - (start.minY + start.maxY) * 0.5;
        double tz = (end.minZ + end.maxZ) * 0.5 - (start.minZ + start.maxZ) * 0.5;
        // latch.n is the exit rectangle's normal mapped into destination space, and a
        // portal normal faces the side you enter from -- so +n points back through the
        // doorway and -n is the direction the body left along.
        return tx * latch.nx + ty * latch.ny + tz * latch.nz;
    }

    /** Positional tolerance when matching a candidate portal against the plate's rectangle. */
    private static final double PLATE_IDENTITY_TOLERANCE = 1.0e-3;

    /**
     * Geometric identity: is {@code portal} the doorway this plate was built on?
     *
     * <p>The plate is the EXIT rectangle, produced by mapping the entered portal's own
     * rectangle through its isometry. The portal entity standing on that rectangle is a
     * different entity with a different UUID, so identity cannot be a UUID comparison --
     * that is why the old {@code latch.portalId.equals(portal.getUUID())} test could never
     * fire for the one doorway it existed to protect.
     *
     * <p>Both coincident faces of the rectangle match, because the normal test uses the
     * absolute dot product. That is required rather than incidental: the back face is
     * precisely the face a reversed re-entry would come through.
     */
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
        Vec3 n = portal.getNormal();
        return Math.abs(n.x * latch.nx + n.y * latch.ny + n.z * latch.nz) >= 0.999999;
    }

    /**
     * ONE CROSSING HAS TWO RECTANGLES, AND THIS PLATE BELONGS TO BOTH OF THEM.
     *
     * <p>{@link #armExitClearance} files a latch under the UUID of the portal the body flew
     * INTO, while the plate it builds is the rectangle the body came OUT of -- a different
     * entity, at a different place, with a different id. Each of the two identity tests
     * alone therefore answers "not mine" about one of the two portals of the very crossing
     * the plate describes:
     *
     * <ul>
     *   <li>the geometric test ({@link #plateIsRectangleOf}) recognises the exit rectangle
     *       and its coincident back face, which is what a reversed re-entry comes through;
     *   <li>the UUID test recognises the ENTRANCE face, which is the face the straddle
     *       session is keyed on and therefore the face every session-scoped caller asks
     *       about.
     * </ul>
     *
     * <p>Asking only the geometric question is why {@link #touchesPortalVirtually} could
     * never be true for the doorway it exists to describe: its caller holds the entrance
     * face. Overlap against the plate is still required by every caller, so accepting both
     * forms widens nothing geometrically -- it only stops the predicate from disowning half
     * of its own crossing.
     */
    private static boolean latchCoversPortal(
        ExitLatch latch, @org.jetbrains.annotations.Nullable Portal portal
    ) {
        if (portal == null) return false;
        if (latch.portalId != null && latch.portalId.equals(portal.getUUID())) return true;
        return plateIsRectangleOf(latch, portal);
    }

    /**
     * THE VIRTUAL EXTENSION, AS A QUESTION ABOUT CONTACT -- NOT AS GEOMETRY.
     *
     * <p>While a body is leaving a doorway it counts as TOUCHING that doorway, even once it
     * is geometrically clear of the plane, right up until it has cleared the 0.01. Nothing
     * about the portal's own rectangle moves; this is only the shared answer to "is this
     * sub-level still in that portal?", and every consumer must use it instead of testing
     * raw contact for itself.
     *
     * <p>This is the rule that has to hold for ALL sub-levels: the session stays open across
     * that band, so a consumer that decides the body no longer touches the portal disagrees
     * with the session and renders/handles it as if it had already left. That disagreement is
     * the Creative Physics Staff artefact on a body that has practically exited, and it is
     * why the band cannot be private to the detector.
     *
     * <p>Identity is {@link #latchCoversPortal}, not the plate rectangle alone. The only
     * caller that asks this question holds the ENTRANCE face -- the face the straddle session
     * is keyed on -- and the plate is built on the EXIT rectangle, so the rectangle test
     * alone answered false for every ordinary portal pair and the virtual band existed in
     * the documentation only.
     */
    public static boolean touchesPortalVirtually(
        ServerSubLevel airship, @org.jetbrains.annotations.Nullable Portal portal
    ) {
        if (airship == null || portal == null) return false;
        ExitLatch latch = liveExitLatch(airship);
        if (latch == null || !latchCoversPortal(latch, portal)) return false;
        return latch.overlaps(currentBounds(airship));
    }

    /** Removes trails for ships no longer visited by the hosting container. */
    public static void pruneTrails() {
        TRAILS.entrySet().removeIf(entry -> {
            if (entry.getValue().lastSeenTick == trailTick) return false;
            // The buffered exit belongs to the trail it seeds. A body the container stopped
            // visiting has no next capture to consume it, so it is retired here rather than
            // by a tick budget -- lifecycle, not arithmetic.
            EXIT_ANCHORS.remove(entry.getKey());
            return true;
        });
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
            trail.seededFromExit = false;
            ExitAnchor anchor = liveAnchor(id);
            if (trail.rebasedThisTick && anchor != null) {
                // MONOLITHIC REHOME. The previous tick ended with a parent flip, so the
                // body's own lastPose() is the pre-flip source pose and is unusable, while
                // trail.end is where the body ALREADY IS -- possibly past the next portal.
                // Starting there is what breaks the A-to-B sweep at high speed.
                //
                // The buffered exit pose is the honest point A: the construction really was
                // at the exit aperture during this segment (the player sees exactly that as
                // interpolation), it simply never coincided with a tick boundary. Seeding it
                // makes the new segment span exit-aperture -> current pose, so a body that
                // materialised behind the NEXT portal sweeps through that portal's aperture
                // on this very tick and starts its transition with no extra machinery.
                trail.start.set(anchor.pose);
                trail.hasOlder = false;
                trail.rebasedThisTick = false;
                // THE BUFFER DIES THE INSTANT IT IS USED -- and it is used HERE, in the
                // very first thing the tick does, while everything that has to know about
                // it (the portal scan, the session teardown, the trail reset) runs later
                // in the same tick. Asking the anchor map after this point therefore
                // always answered "no buffer". Record the fact on the segment itself:
                // THIS segment starts at an exit aperture.
                trail.seededFromExit = true;
                EXIT_ANCHORS.remove(id);
            } else if (trail.rebasedThisTick) {
                // HEART FIX. After a parent flip, airship.lastPose() is the pose the body
                // held in the OLD chart. Joining it to the new logicalPose() produces a
                // segment that jumps across the portal in raw coordinates -- a sweep of
                // arbitrary length through geometry the body never touched. The detector
                // then rejects every candidate along it (or mis-sweeps), which is exactly
                // why a body at extreme speed completed roughly ONE crossing per tick and
                // a tight portal loop took ~11 seconds to unwind no matter how high the
                // per-tick crossing cap was set. The rebased end is already expressed in
                // the new chart and IS the correct start of the next segment.
                trail.start.set(trail.end);
                trail.hasOlder = false;
                trail.rebasedThisTick = false;
            } else {
                trail.start.set(airship.lastPose());
            }
            trail.end.set(airship.logicalPose());
        }
        trail.lastSeenTick = trailTick;
    }

    /** Forget source-frame history after a parent flip; it cannot be joined to destination poses. */
    public static void forgetTrail(ServerSubLevel airship) {
        TRAILS.remove(airship.getUniqueId());
        EXIT_LATCHES.remove(airship.getUniqueId());
        EXIT_ANCHORS.remove(airship.getUniqueId());
    }

    /**
     * A completed source-side exit starts a fresh trail at its current pose. This discards
     * the just-unwound portal segment, so it cannot claim the coincident back face next tick.
     *
     * <p>The reset also counts as a re-base for the NEXT capture. Without that flag the
     * following tick rebuilt the segment from {@code airship.lastPose()} -- the pose the
     * body held before the exit, in the chart it was in before the exit -- which resurrects
     * exactly the huge cross-world sweep this reset exists to destroy.
     */
    public static void resetTrail(ServerSubLevel airship) {
        // A BUFFERED EXIT OUTRANKS A RESET. This reset exists to destroy a segment that
        // spans a seam; the anchor is the opposite thing -- the destination-frame START of
        // the new segment. Dropping it here deleted the doorway history one tick after
        // building it, and the caller that resets also stops scanning portals for this body,
        // so that tick produced no rehome at all: two ticks per doorway instead of one.
        // Re-seed from the buffer instead. Same settlement, no lost tick.
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
            // ALREADY THE EXIT SEGMENT. Its start IS the doorway the body just came out
            // of, which is precisely the history a reset would destroy. Keep it.
            return;
        }
        trail.older.set(airship.logicalPose());
        trail.start.set(airship.logicalPose());
        trail.end.set(airship.logicalPose());
        trail.hasOlder = false;
        trail.rebasedThisTick = true;
        trail.lastSeenTick = trailTick;
    }

    /**
     * EXIT SETTLEMENT. Not a deferred tick job: the caller runs this the instant the tick's
     * crossing has consumed the physics segment, and it answers one question -- has the body
     * physically left the 0.01 slab of the portal it came out of?
     *
     * <p>If it has, the exit is COMPLETE right now: the latch is dropped and the trail is
     * re-seeded at the pose the body actually holds, so the segment that led up to the exit
     * stops existing at the same instant. The caller then closes the straddle session for
     * the returned portal. That pairing is what makes the back face unreachable without any
     * timing window at all:
     * <ul>
     *   <li>still inside the slab -> the session is still open and owns the doorway, so
     *       nothing else may claim the body;</li>
     *   <li>outside the slab -> the session is closed AND the trail no longer contains a
     *       single point behind the plane, so even an instant reversal has no swept segment
     *       that could be read as an entry through the opposite face.</li>
     * </ul>
     *
     * <p>RELEASE HERE IS PURELY GEOMETRIC. This used to also treat a latch older than
     * {@link #EXIT_LATCH_MAX_TICKS} as a completed exit, which could never happen: expiry
     * runs once per tick in {@link #beginTrailTick}, before anything calls this, so such a
     * latch has already been removed and the lookup above answers null. The flag only hid
     * the fact that a body parked inside the slab has its exit settled by the transit
     * controller's own reap rather than by a clock in here.
     *
     * @return the portal whose exit just completed, or null when the body is still inside
     *         the slab (or was never latched)
     */
    @org.jetbrains.annotations.Nullable
    public static UUID settleExit(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        ExitLatch latch = EXIT_LATCHES.get(id);
        if (latch == null) return null;
        if (latch.overlaps(currentBounds(airship))) return null;
        EXIT_LATCHES.remove(id);
        // The session is finished, the HISTORY is not. Collapsing the trail onto the current
        // pose (the old behaviour) deleted the only evidence that the body came out of an
        // aperture this tick, which is precisely the state a fast body needs: it is already
        // far past the exit frame, so a point-trail leaves the next portal undetectable.
        // Re-seed from the buffered exit pose instead -- same settlement, continuous history.
        reseedTrailFromExitAnchor(airship);
        return latch.portalId;
    }

    /**
     * Replaces the trail with the segment {@code exit aperture -> current pose}, keeping the
     * anchor available for the next capture. This is the join that makes rehome and detection
     * one continuous operation instead of two stages separated by a lost tick:
     *
     * <ul>
     *   <li>the flip happens now, at the exit aperture;</li>
     *   <li>the segment describing it exists now, so this same tick's remaining evaluation
     *       and the next tick's sweep both see a real A-to-B path;</li>
     *   <li>the next portal on that path is met by the ordinary aperture test.</li>
     * </ul>
     *
     * <p>Falls back to the plain reset when no anchor is live (an ordinary source-side exit,
     * which has no exit aperture to anchor to).
     */
    private static void reseedTrailFromExitAnchor(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        ExitAnchor anchor = liveAnchor(id);
        if (anchor == null) {
            resetTrail(airship);
            return;
        }
        Trail trail = TRAILS.get(id);
        if (trail == null) {
            trail = new Trail(new Pose3d(anchor.pose), new Pose3d(anchor.pose),
                new Pose3d(airship.logicalPose()));
            TRAILS.put(id, trail);
        } else {
            trail.older.set(anchor.pose);
            trail.start.set(anchor.pose);
            trail.end.set(airship.logicalPose());
        }
        trail.hasOlder = false;
        // The next capture still needs the anchor: it, not lastPose(), is that segment's A.
        trail.rebasedThisTick = true;
        trail.seededFromExit = true;
        trail.lastSeenTick = trailTick;
    }

    @org.jetbrains.annotations.Nullable
    private static ExitAnchor liveAnchor(UUID id) {
        return EXIT_ANCHORS.get(id);
    }

    /**
     * Re-bases a trail into the destination frame at the exact crossing time of a transit that
     * has just executed. Forgetting the trail (the previous behaviour) threw away the part of
     * the physics segment lying BEYOND the portal plane, so a body fast enough to cross two
     * portals inside one segment consumed only the first one and travelled the remainder in the
     * wrong chart. The retained remainder is [crossingTime, 1] mapped through the portal
     * isometry; history before the seam is dropped because it cannot be joined to destination
     * poses.
     */
    public static void rebaseTrailThroughPortal(
        ServerSubLevel airship, Portal portal, double crossingTime
    ) {
        UUID id = airship.getUniqueId();
        Trail trail = TRAILS.get(id);
        Pose3d entry;
        // A CROSSING TIME OF ZERO IS NOT A CROSSING TIME, AND USING IT COMPOUNDED ONCE PER LAP.
        //
        // evaluate() reports 0.0 when only the HISTORIC sweep found the hit: the crossing
        // happened at or before this segment began, which is the only honest value in this
        // parameterisation but is not a point ON this crossing. Handing it to interpolate()
        // maps the segment's START -- and after a previous exit that start IS the previous
        // exit anchor (see captureTrail). Every lap's anchor was then the image of the
        // previous lap's anchor under the portal isometry, so a closed loop composed its two
        // maps once per lap and the point crept by the loop's closure defect every time.
        //
        // That is the observed "drip": the overlay's point A climbs away from the doorway lap
        // after lap, in ever smaller visible steps as the body speeds up, until it creeps
        // outside the finite aperture -- at which instant the sweep no longer intersects that
        // doorway, no crossing is detected, and the body falls straight through the portal it
        // was looping through. Nothing else in this class accumulates across laps, which is
        // why the failure had a wall-clock signature rather than a speed one.
        //
        // The body's own post-flip pose is always a point of THIS crossing and comes from the
        // physics rather than from the previous anchor, so it cannot compose. It is the
        // fallback whenever the reported time is unusable; projectOntoExitPlane() still slides
        // it onto the exit rectangle exactly as it does for a real mid-segment crossing, and a
        // genuine finite time inside (0, 1] is used unchanged.
        double crossingFraction = Double.isFinite(crossingTime)
            ? Math.clamp(crossingTime, 0.0, 1.0) : Double.NaN;
        if (trail == null || !Double.isFinite(crossingFraction) || crossingFraction <= EPSILON) {
            // Already expressed in the destination frame: the flip has executed by the time
            // this runs, so this pose needs no mapping.
            entry = new Pose3d(airship.logicalPose());
        } else {
            // ONE POSE, ONE FRAME, ONE MAPPING. The anchor is derived from THIS pose and
            // nothing else. Keeping a second, source-frame copy alive so the anchor could
            // be rebuilt with a different transform is how the buffer ended up in a frame
            // of its own, disagreeing with the very segment it was meant to start.
            entry = IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(
                interpolate(trail.start, trail.end, crossingFraction));
        }
        if (trail == null) {
            trail = new Trail(new Pose3d(entry), new Pose3d(entry), new Pose3d(entry));
            TRAILS.put(id, trail);
        }
        // THE PAST POINT BECOMES THE EXIT POINT. THIS IS THE BACK-FACE PROTECTION.
        //
        // Carrying the pose held just BEFORE the seam through the isometry -- what this used
        // to do -- puts the past point BEHIND the exit rectangle, because before the crossing
        // the body was still short of the entrance. At any real speed a whole segment is
        // several blocks long, so `entryT - 1.0e-3` lands well behind the plane. The detector
        // then reads that point as `oldest`, concludes startedBeforePortalPlane, and the
        // historic sweep older->start crosses the plane in the destination direction. The
        // trail was MANUFACTURING a fresh forward entry through the coincident back face on
        // every single rehome, which is why adding clearance to the exit never helped: the
        // clearance was on `start`, while `older` was deliberately placed behind the plane.
        //
        // So the past point is replaced by the current one, which is the exit anchor and is
        // already 0.01 past the plane. Turning around now sweeps from outside the plane back
        // toward it -- the sweep sees the 0.01 difference and classifies TOWARD_SOURCE, so no
        // passage through the back side exists. There is no point behind the plane left for
        // anything to find: not jitter, not extrapolation, not float noise.
        //
        // Direction evidence is not lost. It now comes from the segment that actually
        // happened in this frame -- anchor -> current pose -- which is genuine outward motion
        // through the face the body really came out of. This is exactly what
        // reseedTrailFromExitAnchor() already does on the settle path; the two paths now
        // agree instead of contradicting each other.
        Pose3d anchor = armExitAnchor(airship, portal, entry);
        trail.older.set(anchor);
        trail.start.set(anchor);
        trail.hasOlder = false;
        // Snapshot of the previous position + the 0.01 band it must clear before this exit
        // counts. Without it a body at absurd speed can be re-admitted through the coincident
        // back face on the very next evaluation.
        armExitClearance(airship, portal);
        trail.end.set(airship.logicalPose());
        trail.rebasedThisTick = true;
        trail.lastSeenTick = trailTick;
    }

    /**
     * Predictive aperture arming. Answers whether the current segment, continued forward by
     * {@code lookaheadSegments}, drives the body into this finite aperture from the source
     * side. The straddle seam (image collider plus clip regions) is then opened one segment
     * early, so a body moving several blocks per tick meets an already porous doorway instead
     * of the intact source-side terrain behind the portal plane.
     */
    public static boolean willEnterAperture(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal,
        double apertureMargin, double lookaheadSegments
    ) {
        if (lookaheadSegments <= 0.0) return false;
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(airship);
        if (blocks.isEmpty()) return false;
        Trail trail = trail(airship);
        Pose3d ahead = interpolate(trail.start, trail.end, 1.0 + lookaheadSegments);
        SweepResult predicted = sweepSegment(portal, blocks,
            new Frame(trail.end), new Frame(ahead), sourceToDestNormal, apertureMargin);
        return predicted.intersects()
            && predicted.direction() == SweepDirection.TOWARD_DESTINATION;
    }

    /**
     * Position lerp with orientation slerp. {@code t} may exceed 1 to extrapolate the segment
     * forward; orientation is clamped at the segment end because extrapolated spin is not
     * evidence, only translation is used for lookahead admission.
     */
    private static Pose3d interpolate(Pose3dc from, Pose3dc to, double t) {
        Pose3d out = new Pose3d(from);
        out.position().set(
            from.position().x() + (to.position().x() - from.position().x()) * t,
            from.position().y() + (to.position().y() - from.position().y()) * t,
            from.position().z() + (to.position().z() - from.position().z()) * t);
        org.joml.Quaterniond rotation = new org.joml.Quaterniond(from.orientation());
        rotation.slerp(new org.joml.Quaterniond(to.orientation()), Math.clamp(t, 0.0, 1.0));
        out.orientation().set(rotation);
        return out;
    }

    /**
     * Buffers the FAKE POINT A of the next segment and returns it.
     *
     * <p>Placement rules, in the order they matter:
     *
     * <ol>
     *   <li><b>Relative to how THIS body leaves.</b> The anchor is the body's own crossing
     *       pose carried through the portal, not the portal's centre and not a rim around
     *       the plane edges. Two bodies leaving through opposite corners of the same doorway
     *       get two different anchors, which is the only placement that keeps the following
     *       sweep collinear with the motion that actually happened.</li>
     *   <li><b>Valid for differently sized rectangles.</b> The pose arrives here already
     *       carried through the portal by {@code StraddleMapping} -- the SAME transform
     *       that produced the segment this anchor starts -- so the point lands at the same
     *       relative spot of the destination rectangle however that rectangle is sized.
     *       Deriving it a second time with a different transform is what made the buffer
     *       disagree with its own segment, so there is exactly one mapping now.</li>
     *   <li><b>Placed ON the rectangle, along the exit direction only.</b> The pose is slid
     *       along {@link Portal#getContentDirection()} until the BODY's rearmost point sits
     *       exactly {@code EXIT_ANCHOR_OFFSET} past the plane. Only that one component is
     *       touched; the plane edges are never grown, exactly like the session-hold band, so
     *       the anchor can neither sit behind the exit face (where it would read as an
     *       approach through the coincident back face) nor spill sideways out of the
     *       doorway.</li>
     * </ol>
     *
     * <p>The buffer lives until the next capture consumes it -- no tick budget, no way to
     * expire between the flip that arms it and the sweep that needs it. Every tick can
     * therefore flip, settle and re-arm without ever handing the sweep a segment that begins
     * where the body already is.
     *
     * @param mappedCrossingPose the crossing pose already carried through the portal; the
     *                           anchor is this pose plus the clearance nudge
     * @return the anchor pose, ready to be used as the segment start
     */
    public static Pose3d armExitAnchor(
        ServerSubLevel airship, Portal portal, Pose3dc mappedCrossingPose
    ) {
        Pose3d anchor = projectOntoExitPlane(airship, portal, mappedCrossingPose);
        EXIT_ANCHORS.put(airship.getUniqueId(), new ExitAnchor(
            new Pose3d(anchor), portal == null ? null : portal.getUUID()));
        return anchor;
    }

    /**
     * The same point A geometry, storing nothing: slide a destination-frame pose along the
     * exit direction until the BODY's rearmost point sits exactly {@code EXIT_ANCHOR_OFFSET}
     * past the exit rectangle.
     *
     * <p>Split out of {@link #armExitAnchor} so the flip itself can reuse it. A rehome that
     * lands the body far past the doorway is not a body that APPEARED far away -- it is a
     * body that flew out fast. Feeding this pose to the previous-pose endpoint makes every
     * consumer that reads motion as {@code lastPose -> logicalPose} describe exactly that
     * flight, instead of the zero-length motion {@code updateLastPose()} leaves behind.
     *
     * @param destinationFramePose any pose ALREADY in the destination frame
     * @return a new pose on the exit rectangle; the two in-plane components are untouched
     */
    public static Pose3d projectOntoExitPlane(
        ServerSubLevel airship, Portal portal, Pose3dc destinationFramePose
    ) {
        Pose3d anchor = new Pose3d(destinationFramePose);
        if (portal != null) {
            Vec3 exitDirection = portal.getContentDirection();
            double length = exitDirection.length();
            List<BlockPos> blocks = IplPortalVolumeCache.blocks(airship);
            if (length > EPSILON && !blocks.isEmpty()) {
                double nx = exitDirection.x / length;
                double ny = exitDirection.y / length;
                double nz = exitDirection.z / length;
                // ON THE EXIT RECTANGLE, MEASURED ON THE BODY.
                //
                // The crossing pose cannot be used as it arrives. entryTime is the moment
                // a swept block first touches the plane BAND, and that band is a whole
                // support plus hysteresis deep, so the pose it produces still sits most of
                // a block SHORT of the rectangle, on the entry side. Adding 0.01 to that
                // leaves point A behind the doorway it is supposed to mark.
                //
                // It also must not be projected by its own position(): Pose3d.position()
                // is the image of plot-local (0,0,0) -- a CORNER OF THE PLOT -- while Frame
                // builds every block as pose.transformPosition(block + 0.5). Pinning that
                // corner to the plane shifts the volume by an arbitrary multi-block amount.
                //
                // So measure the body exactly the way the sweep measures it: per-block
                // centres from the pose basis, minus the OBB support. Then slide the pose
                // along the exit direction until the body's REARMOST point sits exactly
                // 0.01 past the plane. The two in-plane components are never touched, so
                // the anchor keeps the spot this body actually left through, and because
                // the plane point comes from the portal's own mapping the result is the
                // same relative spot on a destination rectangle of any size.
                IplStraddlePoseMap.StraddleMapping exitFrame =
                    IplStraddlePoseMap.StraddleMapping.of(portal);
                Vec3 planePoint = exitFrame.mapPoint(portal.getOriginPos());
                Frame frame = new Frame(anchor);
                double support = frame.obbSupport(nx, ny, nz);
                double rear = Double.POSITIVE_INFINITY;
                for (BlockPos block : blocks) {
                    double distance = (frame.centerX(block) - planePoint.x) * nx
                        + (frame.centerY(block) - planePoint.y) * ny
                        + (frame.centerZ(block) - planePoint.z) * nz;
                    rear = Math.min(rear, distance - support);
                }
                if (Double.isFinite(rear)) {
                    // Whole-body translation along n: every block centre moves by the same
                    // amount, so rear lands exactly on EXIT_ANCHOR_OFFSET. The body is then
                    // WHOLLY clear of the rectangle, which is also what stops point A from
                    // ever reading as a fresh forward crossing of the doorway it just left
                    // -- the failure that dropped bodies through the bottom of a loop.
                    double correction = EXIT_ANCHOR_OFFSET - rear;
                    anchor.position().set(
                        anchor.position().x() + nx * correction,
                        anchor.position().y() + ny * correction,
                        anchor.position().z() + nz * correction);
                }
            }
        }
        return anchor;
    }

    /**
     * Snapshots the body's post-transit position and opens the exit-clearance band for the
     * portal it just came through. Called for every executed transit, so the latch always
     * describes the most recent crossing.
     */
    public static void armExitClearance(ServerSubLevel airship, Portal portal) {
        if (portal == null) return;
        // CORRECTED SEMANTICS. This is an ABSOLUTE thickness (0.01 blocks), not a fraction
        // of the portal's width: the plane is given a real 0.01-thick body instead of being
        // a zero-thickness surface, and the band is symmetric -- 0.01 on the exit side and
        // 0.01 on the opposite side. A body must clear the whole slab before its exit is
        // counted, which is what makes fast back-and-forth re-entry impossible regardless
        // of how wide the portal happens to be.
        double clearance = Math.max(EXIT_CLEARANCE_THICKNESS, MIN_EXIT_CLEARANCE);
        // Resetting the swept buffer is the other half of the guarantee: the stale sweep
        // sample from BEFORE the crossing is what a re-entry would otherwise be tested
        // against, so it is dropped together with the snapshot being armed here.
        IplEnteringVolumeVisualization.clearBuffer(airship);
        // The PLATE. ONLY the normal direction gets thickness: the zero-thickness quad
        // becomes a slab of half-thickness `clearance` (0.01 on each face), while the
        // rectangle keeps the portal's own width and height. Widening W/H as well made
        // the plate spill outside the doorway, so a body falling PAST a portal (a loop of
        // several portals) kept re-triggering a latch that belongs to a crossing it never
        // made.
        // THE PLATE BELONGS TO THE EXIT, NOT TO THE ENTRANCE. This used to be built from
        // portal.getOriginPos() and the source-frame axes -- the rectangle the body flew
        // INTO -- while the body itself is now standing at portal.getDestPos(). Two
        // consequences, and together they are the whole bug:
        //
        //  1. The back-face guard never guarded anything. The body is nowhere near the
        //     entrance rectangle after a transit, so the slab it was tested against was
        //     empty space.
        //  2. In a two-portal loop (floor portal A, ceiling portal B, A -> B) the plate sat
        //     exactly on A -- which is precisely where the body arrives one fall later. The
        //     NEXT, entirely legitimate crossing of A was therefore refused, every time.
        //     While the body was slow enough to need less than one tick per lap that never
        //     showed; the moment per-tick travel reached the distance between the portals
        //     the crossing had to be resolved inside the same tick, the refusal killed the
        //     chain, and the body fell straight through the floor portal. That is the fixed
        //     ~200-tick lifetime of the loop, independent of tick rate: it is a SPEED
        //     threshold (travel per tick >= portal spacing), which is exactly why running
        //     the server at 5 TPS bought wall-clock seconds and not a single extra lap.
        //
        // The plate is therefore the DESTINATION rectangle, mapped through the portal's own
        // isometry: the surface the body actually came out of, and the only surface a
        // reversed re-entry can happen through.
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
            clearance, trailTick));
    }

    /**
     * True while the body still sits inside the exit-clearance band of its last crossing.
     * The latch self-clears the moment the band is passed (or after a hard tick budget), so
     * this is safe to call every tick and needs no explicit teardown.
     *
     * @param portal when non-null, only a latch belonging to THIS portal answers true;
     *               pass null to ask "is this body still leaving anything at all?"
     */
    public static boolean withinExitClearance(ServerSubLevel airship, Portal portal) {
        ExitLatch latch = liveExitLatch(airship);
        if (latch == null) return false;
        // Test the POST-CROSSING pose only. Using sweptBounds() here was the bug: that
        // box also contains trail.start / trail.older, i.e. the pose the body held BEFORE
        // the crossing, which by construction lies on the other side of the plate. The
        // union therefore always intersected the slab and the latch could only ever be
        // released by the tick budget -- which is why the loop died after a fixed number
        // of seconds instead of after the body physically left the doorway.
        //
        // This method is a PREDICATE. It must not mutate the trail: the segment tail is
        // still needed by the other portals scanned in this same tick. Trail re-seeding
        // belongs to the caller that actually completes the exit.
        //
        // Both former `EXIT_LATCHES.remove(id)` calls are gone from this method for that
        // reason -- expiry is now done once per tick in beginTrailTick(), and geometric
        // release is done by settleExit(), which is the caller that actually completes the
        // exit and re-seeds the trail.
        if (!latch.overlaps(currentBounds(airship))) return false;

        // IDENTITY IS GEOMETRIC, AND IT IS STILL PER PORTAL.
        //
        // Two independent defects meet on this line, and fixing either one alone breaks the
        // other:
        //
        //  1. `latch.portalId.equals(portal.getUUID())` could never be true for the portal
        //     actually standing on the plate. armExitClearance() files the latch under the
        //     UUID of the portal the body flew INTO, while the plate it builds is the
        //     rectangle the body came OUT of -- a different entity. The guard answered
        //     "that is somebody else's exit" about the one doorway it exists to protect,
        //     which is why back-side re-entry was never actually prevented.
        //
        //  2. Deleting the test instead turns this predicate into "is this body leaving
        //     anything at all?", which also refuses the body's NEXT, entirely legitimate
        //     doorway. The transit scan turns that refusal into `continue`, the tick ends
        //     with no rehome, and the body needs two ticks per doorway -- halving loop
        //     survival, exactly as SableTransitController's back-face guard warns.
        //
        // So the scoping is kept, but expressed against the plate's own GEOMETRY: true for
        // the portal whose rectangle IS this plate and for its coincident twin, false for
        // every other portal in the scan.
        //
        // This is deliberately the rectangle test alone, NOT latchCoversPortal(): the
        // entrance face belongs to the crossing but is not a surface a reversed re-entry can
        // come through, and admitting it here would re-refuse the body's next doorway in a
        // loop whose next entrance happens to be the face just used.
        return plateIsRectangleOf(latch, portal);
    }

    public static void clearTrails() {
        TRAILS.clear();
        EXIT_LATCHES.clear();
        EXIT_ANCHORS.clear();
        trailTick = 0L;
    }

    /** Debug-only buffered exit pose ("fake point A"). Null when no exit is pending. */
    @org.jetbrains.annotations.Nullable
    public static Pose3dc bufferedExitAnchor(ServerSubLevel airship) {
        ExitAnchor anchor = liveAnchor(airship.getUniqueId());
        return anchor == null ? null : anchor.pose;
    }

    /** The doorway a buffered exit anchor belongs to, or null when none is pending. */
    @org.jetbrains.annotations.Nullable
    public static UUID bufferedExitAnchorPortal(ServerSubLevel airship) {
        ExitAnchor anchor = liveAnchor(airship.getUniqueId());
        return anchor == null ? null : anchor.portalId;
    }

    /**
     * True while a buffered exit pose is waiting to be the next segment's point A.
     *
     * <p>Callers use this to tell a body that JUST CAME OUT of a doorway apart from a body
     * whose trail is genuinely stale. The first one owns a valid destination-frame segment
     * and must keep being evaluated against every remaining portal in the same tick; only
     * the second one may have its trail thrown away.
     *
     * <p>THIS IS A FACT ABOUT THE BODY, NOT ABOUT A PORTAL. It says the segment begins at
     * some doorway; it cannot say that the segment began behind the plane of the portal
     * currently being evaluated. Using it as entry evidence for a candidate face lets a body
     * that merely flew PAST a doorway be rehomed through it, so the transit controller asks
     * it only about trail lifecycle and takes source-side entry from the geometry.
     */
    public static boolean hasBufferedExit(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        if (EXIT_ANCHORS.containsKey(id)) return true;
        // ORDER MATTERS. The anchor map is emptied by the capture that consumes it, and
        // that capture is the FIRST thing the tick does -- the scan that needs this answer
        // always runs after it. Reading only the map made this predicate permanently false
        // during the scan, so the caller went on resetting the trail and abandoning the
        // scan exactly as before, and the buffer changed nothing. The segment carries the
        // fact instead, for the whole tick.
        Trail trail = TRAILS.get(id);
        return trail != null && trail.seededFromExit;
    }

    /**
     * Debug-only prior endpoint: exactly what the overlay draws as "point A".
     *
     * <p>This must never fall through to the live body. A trail seeded from the exit buffer
     * suppresses {@code older} deliberately -- there is no older pose, the segment BEGINS at
     * the doorway -- and returning null there made the caller fall back to the body's own
     * position. Point A then appeared to ride the body all the way down to the bottom portal
     * and snap back up to the doorway on alternating ticks. That is a property of the
     * OVERLAY, not of the detector, and it is what a low tick rate makes look like a slow
     * drift. Report the live anchor first, then the retained older endpoint, and finally the
     * exit-seeded segment start -- which is the anchor the capture just consumed.
     */
    @org.jetbrains.annotations.Nullable
    public static Pose3dc bufferedPose(ServerSubLevel airship) {
        ExitAnchor anchor = liveAnchor(airship.getUniqueId());
        if (anchor != null) return anchor.pose;
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail == null) return null;
        if (trail.hasOlder) return trail.older;
        return trail.seededFromExit ? trail.start : null;
    }

    /** World bounds of the body at fraction {@code t} of the retained segment. */
    public static AABB boundsAt(ServerSubLevel airship, double t) {
        Trail trail = trail(airship);
        double clamped = Double.isFinite(t) ? Math.clamp(t, 0.0, 1.0) : 0.0;
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, interpolate(trail.start, trail.end, clamped));
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    /** Length in blocks of the retained segment (translation only). */
    public static double segmentLength(ServerSubLevel airship) {
        Trail trail = trail(airship);
        return trail.start.position().distance(trail.end.position());
    }

    /**
     * GEOMETRIC RE-ENTRY GUARD. Asks whether the body would ENTER a new doorway from
     * inside the 0.01 slab of the doorway it just came out of.
     *
     * <p>The identity-based guard ({@link #withinExitClearance}) only ever refused the
     * portal that was just used and its coincident twin, and that is not what breaks a
     * loop. A loop is built from a PAIR of portals: the instant the body lands at the
     * exit of A it is standing in the aperture of B, whose own back face leads straight
     * back to A. Both faces belong to portals the guard considered unrelated, so tick
     * after tick the body was flipped A-B-A-B... at zero travelled distance, each flip
     * paying a full parent handoff for motion that never happened.
     *
     * <p>This test is geometric, so a genuine multi-portal loop is untouched: the entry
     * point of a portal even 0.02 blocks away from the previous exit is outside the slab
     * and is admitted immediately. Only an entry that happens INSIDE the slab -- i.e. at
     * zero travelled distance from the exit -- is refused, and only until the body has
     * physically left the slab, which at loop speeds takes a fraction of a tick.
     *
     * @param entryTime fraction of the retained segment at which the entry occurs
     */
    public static boolean entryWithinExitSlab(ServerSubLevel airship, double entryTime) {
        // Read-only latch access: this predicate is the second one the transit scan runs
        // per portal, and its old self-clearing expiry was the other half of the
        // scan-order hole documented in beginTrailTick().
        ExitLatch latch = liveExitLatch(airship);
        if (latch == null) return false;
        // THE ONLY GUARD THAT COVERS THE EXIT RECTANGLE, so it stays absolute.
        //
        // armExitClearance files the latch under the UUID of the portal the body flew
        // INTO, while the plate it builds is the rectangle the body came OUT of. The
        // identity test can therefore never answer for the portal that actually sits at
        // that rectangle -- the ids simply do not match, for it or for its coincident
        // twin. Relaxing this test to "refuse only an entry within 0.01 of the buffered
        // anchor" left the exit rectangle completely unguarded: the segment that starts at
        // the exit is clipped to t = 0 by the plane band, its blocks straddle that
        // rectangle, and the sweep reads a fresh forward crossing of it. The body was then
        // rehomed a full loop DOWNWARD and fell out through the bottom of the loop.
        //
        // A genuine next portal is untouched: its entry pose lies a real distance down the
        // segment, outside the 0.01 plate, and is admitted immediately.
        //
        // ...AND SO IS A GENUINE RE-CROSSING OF THIS SAME RECTANGLE, which is the part that
        // was missing and the reason the plate had to be kept down to three ticks. Position
        // alone cannot distinguish "leaving" from "coming back in": both happen at the same
        // rectangle. DIRECTION can, and it is the property that actually defines a back-side
        // entry. A body that is still travelling out of the doorway is finishing a crossing;
        // a body travelling back into it is doing the thing this guard exists to refuse.
        //
        // With that distinction in place the plate no longer has to expire quickly, so it
        // survives a whole burst of jitter (see EXIT_LATCH_MAX_TICKS) while a fast portal
        // loop -- always moving forward -- is never refused at all.
        if (backwardTravelIntoPlate(airship, latch) <= EPSILON) return false;

        if (latch.overlaps(boundsAt(airship, entryTime))) return true;

        // boundsAt() coerces a non-finite t to 0.0, so a NaN entryTime (several evaluate()
        // paths produce one) used to be silently tested at the segment start -- which for a
        // reversal is the far side of the plate, i.e. it slipped straight through the guard.
        // An entry with no usable time is checked against the current pose instead of being
        // waved past.
        return !Double.isFinite(entryTime) && latch.overlaps(currentBounds(airship));
    }

    /** Broad phase of the CURRENT pose only, with no swept history. */
    public static AABB currentBounds(ServerSubLevel airship) {
        Trail trail = trail(airship);
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, trail.end);
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
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
                SweepDirection.NONE, SweepDirection.NONE, Double.NaN);
        }

        Trail trail = trail(airship);
        Frame start = new Frame(trail.start);
        Frame end = new Frame(trail.end);
        Frame older = trail.hasOlder ? new Frame(trail.older) : null;
        // THE PORTAL PLANE IS THE PORTAL PLANE, AND NOTHING HERE DISPLACES IT.
        //
        // Moving this plane by 0.01 was wrong. The 0.01 is a VIRTUAL extension -- a rule
        // about what still counts as being in the doorway -- not a geometric offset of the
        // portal, and baking it in here changes the answer for bodies that have nothing to
        // do with that doorway and for faces that were never crossed.
        //
        // What actually carries the 0.01 is the TRAIL. rebaseTrailThroughPortal replaces the
        // past point with the exit anchor, which already sits 0.01 past this plane, so a body
        // that turns around sweeps from OUTSIDE the plane back toward it: the sweep sees the
        // 0.01 difference, reads TOWARD_SOURCE, and no entry through the back face exists to
        // be found. The geometry stays honest and the history does the work.
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
            recent.direction(),
            // entryTime is ALWAYS a fraction of the CURRENT segment (start -> end).
            // A hit that only the HISTORIC sweep found is a fraction of older -> start,
            // a different segment entirely. Passing it on unchanged meant
            // interpolate(start, end, t) picked an arbitrary point of the current
            // segment -- in a tight vertical loop, visibly around the middle of the loop,
            // which is exactly where the rehome anchor was being planted. Such a hit
            // happened at or before this segment began, so 0 is its only correct
            // expression in this parameterisation. Consumers must therefore treat 0 as
            // "not a point of this crossing" rather than as the segment start: see
            // rebaseTrailThroughPortal, where doing the latter made every lap's exit
            // anchor the image of the previous lap's anchor.
            Double.isFinite(recent.towardTime()) ? recent.towardTime()
                : (Double.isFinite(sweep.towardTime()) ? 0.0 : Double.NaN));
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

    /**
     * Buffered exit pose plus the doorway and tick it belongs to. One per body: only the
     * most recent exit can be the point A of the next segment.
     */
    private static final class ExitAnchor {
        final Pose3d pose;
        @org.jetbrains.annotations.Nullable final UUID portalId;

        ExitAnchor(Pose3d pose, @org.jetbrains.annotations.Nullable UUID portalId) {
            this.pose = pose;
            this.portalId = portalId;
        }
    }

    /** Snapshot of the previous (crossing-time) position plus the band it must clear. */
    private static final class ExitLatch {
        final UUID portalId;
        final double ox, oy, oz;
        final double nx, ny, nz;
        final double wx, wy, wz;
        final double hx, hy, hz;
        final double halfW, halfH, halfThickness;
        final long tick;

        ExitLatch(UUID portalId,
                  double ox, double oy, double oz,
                  double nx, double ny, double nz,
                  double wx, double wy, double wz,
                  double hx, double hy, double hz,
                  double halfW, double halfH, double halfThickness, long tick) {
            this.portalId = portalId;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.wx = wx; this.wy = wy; this.wz = wz;
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.halfW = halfW;
            this.halfH = halfH;
            this.halfThickness = halfThickness;
            this.tick = tick;
        }

        /**
         * Conservative separating-axis test of a world AABB against the plate. The plate
         * is an oriented box: half-thickness along the portal normal, half-width and
         * half-height along the portal's own axes, each already grown by the 0.01 margin.
         * Testing the body's SWEPT bounds (not just its current pose) is deliberate --
         * a body fast enough to pass the whole plate inside one segment must still be
         * seen as having been inside it.
         */
        boolean overlaps(AABB box) {
            double cx = (box.minX + box.maxX) * 0.5 - ox;
            double cy = (box.minY + box.maxY) * 0.5 - oy;
            double cz = (box.minZ + box.maxZ) * 0.5 - oz;
            double ex = (box.maxX - box.minX) * 0.5;
            double ey = (box.maxY - box.minY) * 0.5;
            double ez = (box.maxZ - box.minZ) * 0.5;
            double dN = cx * nx + cy * ny + cz * nz;
            double sN = Math.abs(nx) * ex + Math.abs(ny) * ey + Math.abs(nz) * ez;
            if (Math.abs(dN) > halfThickness + sN) return false;
            double dW = cx * wx + cy * wy + cz * wz;
            double sW = Math.abs(wx) * ex + Math.abs(wy) * ey + Math.abs(wz) * ez;
            if (Math.abs(dW) > halfW + sW) return false;
            double dH = cx * hx + cy * hy + cz * hz;
            double sH = Math.abs(hx) * ex + Math.abs(hy) * ey + Math.abs(hz) * ez;
            return Math.abs(dH) <= halfH + sH;
        }
    }

    private static final class Trail {
        final Pose3d older;
        final Pose3d start;
        final Pose3d end;
        boolean hasOlder;
        /**
         * Set by {@link #rebaseTrailThroughPortal}; consumed by the next capture. Marks
         * that this trail's end is already in a post-flip chart, so the pre-flip
         * {@code lastPose()} must NOT be used as the following segment's start.
         */
        boolean rebasedThisTick;
        /**
         * This segment's start IS a buffered exit aperture. Unlike {@code EXIT_ANCHORS},
         * which is emptied by the capture that uses the buffer, this survives for the
         * whole tick that has to act on it: the portal scan, the session teardown and the
         * trail reset all run after that capture.
         */
        boolean seededFromExit;
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
