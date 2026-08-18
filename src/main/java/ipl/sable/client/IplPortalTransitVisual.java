package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync.RemoteCallables.PortalMapping;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE VISUAL SIDE OF A PARENT FLIP: it makes a rehome look like a fly-through.
 *
 * <h2>What was actually wrong</h2>
 *
 * <p>Three separate defects produced one symptom -- "the object does not visibly travel
 * through the portal; sometimes the far half is missing entirely; at speed it hovers up and
 * down like falling drops, and the visual appears a moment late".
 *
 * <p><b>1. The interpolation was never missing -- it was invisible.</b> Sable's client
 * timeline is a buffer of snapshots stamped with the tracking system's tick, sampled at a
 * DELAYED pointer, and the rendered pose is {@code lerp(lastPose, logicalPose, partialTick)}.
 * The server already hands the client the right two endpoints: it pulls the body's previous
 * pose back onto the exit rectangle, so the motion it publishes is literally "portal plane ->
 * rehome point". A portal map {@code P} is an isometry, and for position-lerp plus slerp
 * <pre>P(lerp(a, b, u)) = lerp(P(a), P(b), u)</pre>
 * so mapping both endpoints and interpolating in the destination chart reproduces the
 * crossing fraction EXACTLY. Nothing needed to be re-derived, forced, or overridden in
 * sable/rapier. What was missing is that during those frames NEITHER HALF OF THE BODY WAS
 * BEING DRAWN, because the render path asked a session store ("is there a live straddle
 * session, and does the visual latch admit it?") instead of asking the geometry. A crossing
 * that completes inside one tick -- a fast body, or a small body whose whole crossing fits in
 * one physics segment -- never produces a straddle session at all, so it had no source of
 * truth and drew nothing. That is 1.1, and it is also exactly why SOME objects never showed
 * their far half (1.3): whether a session exists depends on body size and speed, not on
 * whether the body is visually straddling.
 *
 * <p><b>2. The buffer was being re-mapped.</b> The flip mapped EVERY buffered snapshot
 * through the portal with no notion of which ones were already post-flip. A snapshot produced
 * at the end of the flip tick is already expressed in the new chart, so mapping it again
 * displaces it by one whole portal delta -- in a vertical loop, the full loop height. The
 * interpolator then lerps toward those doubly-mapped entries for a few frames and back again,
 * once per flip: a periodic vertical beat in tick cadence, phase-locked to the camera. That
 * is 1.2, and it is a bookkeeping bug, not a frame-rate artefact. The fix is a WATERMARK:
 * map only snapshots strictly older than the flip.
 *
 * <p><b>3. The flip was deferred, which mixed two charts in one buffer.</b> While a handoff
 * waited for the delayed source geometry to "clear the source plane", arriving
 * destination-frame snapshots were mapped BACKWARD into the source chart. The buffer then
 * held poses from two different charts and the interpolator lerped between them -- and when
 * the deferral finally ended the pose snapped. That is the "visual appears a moment later"
 * part. Handoffs are applied immediately now; the buffer is single-chart at every instant.
 *
 * <h2>What this class does</h2>
 *
 * <ul>
 *   <li>{@link #applyFlip} rebases the interpolator buffer with the watermark, so the delayed
 *       timeline stays continuous ACROSS the portal instead of jumping or beating.</li>
 *   <li>{@link #rebaseIncomingSnapshot} maps late/out-of-order pre-flip snapshots forward
 *       into the current chart. Snapshots travel over UDP; one arriving after the flip used to
 *       inject a source-frame pose into a destination-frame buffer.</li>
 *   <li>{@link #window} exposes the EXIT WINDOW: for as long as the interpolated body still
 *       straddles the exit rectangle, both halves are drawn -- the near half natively in the
 *       destination chart, the far half as a projection back into the source chart -- decided
 *       purely from the rendered pose, with no session, latch or prediction involved.</li>
 * </ul>
 */
public final class IplPortalTransitVisual {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    /**
     * The rendered body must clear the exit rectangle by this much before the window closes.
     * Same 0.01 the server uses to settle an exit, for the same reason: a body sitting exactly
     * ON the plane must not be judged to have left it.
     */
    private static final double EXIT_VISUAL_CLEARANCE = 0.01;

    /** Snapshots older than this many ticks can no longer be sampled, so flips can be dropped. */
    private static final int FLIP_RETENTION_TICKS = 60;

    /** Hard cap so a stalled or despawned body can never keep a window open forever. */
    private static final long WINDOW_MAX_AGE_MS = 5_000L;

    private IplPortalTransitVisual() {}

    /** One applied parent flip, kept only as long as a snapshot could still predate it. */
    private record Flip(int tick, PortalMapping mapping) {}

    /** The live exit window of a body: everything needed to draw both halves. */
    private static final class Window {
        final PortalMapping mapping;
        @Nullable final Portal portal;
        final ResourceKey<Level> sourceDim;
        /** Destination rectangle; keeps the half the body has already reached. */
        final Plane keepDest;
        /** Source rectangle; keeps the half that has not come out yet. */
        final Plane keepSource;
        final long openedAtMs;

        Window(PortalMapping mapping, @Nullable Portal portal, ResourceKey<Level> sourceDim,
               Plane keepDest, Plane keepSource) {
            this.mapping = mapping;
            this.portal = portal;
            this.sourceDim = sourceDim;
            this.keepDest = keepDest;
            this.keepSource = keepSource;
            this.openedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * WRITTEN on the client thread (packet handler) and READ from Sable's snapshot receive,
     * which arrives on a network thread. A plain HashMap can be observed mid-resize here: that
     * returns a torn deque, a torn deque yields a garbage mapping, and a garbage mapping is
     * exactly what turns into "Enormous local sub-level collision bounds", then "Raycast too
     * far", and finally a native crash while the vertex buffer is being filled. Each deque is
     * additionally locked on itself, because ArrayDeque iteration is not safe against a
     * concurrent append.
     */
    private static final Map<UUID, ArrayDeque<Flip>> FLIPS =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** Client thread only (it IS the render thread), so a plain map is correct here. */
    private static final Map<UUID, Window> WINDOWS = new HashMap<>();

    /**
     * The two destination-frame poses that bracket a crossing, plus the tick the flip happened
     * on -- read from the same counter that stamps client snapshots.
     *
     * @param flipTick      tracking tick of the flip, or negative when the server could not read it
     * @param exitPlanePose point A: on the exit rectangle, where the body came out
     * @param finalPose     point B: where rehome put the body
     */
    public record Crossing(int flipTick, Pose3d exitPlanePose, Pose3d finalPose) {

        /** Wire format is 1 tick + 14 hex doubles; anything else means "no descriptor". */
        @Nullable
        public static Crossing decode(@Nullable String encoded) {
            if (encoded == null || encoded.isEmpty()) return null;
            try {
                String[] values = encoded.split(";", -1);
                if (values.length != 15) return null;
                int tick = Integer.parseInt(values[0]);
                double[] d = new double[14];
                for (int i = 0; i < d.length; i++) d[i] = Double.valueOf(values[i + 1]);
                Pose3d exit = new Pose3d();
                exit.position().set(d[0], d[1], d[2]);
                exit.orientation().set(d[3], d[4], d[5], d[6]);
                Pose3d finalPose = new Pose3d();
                finalPose.position().set(d[7], d[8], d[9]);
                finalPose.orientation().set(d[10], d[11], d[12], d[13]);
                return new Crossing(tick, exit, finalPose);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Records a flip, rebases the delayed snapshot timeline into the destination chart, and
     * opens the exit window.
     *
     * <p>Called from the handoff path INSTEAD of blindly mapping the whole buffer.
     *
     * @param sourceLevel the parent the body is leaving -- captured here because after the
     *                    flip the body's parent is the destination and the source portal can
     *                    no longer be found from it
     */
    public static void applyFlip(
        ClientSubLevel sub, PortalMapping mapping, @Nullable Crossing crossing,
        @Nullable Portal portal, @Nullable Level sourceLevel
    ) {
        UUID shipId = sub.getUniqueId();
        int flipTick = crossing == null ? -1 : crossing.flipTick();

        rebaseBuffer(sub, mapping, crossing, flipTick);

        if (flipTick >= 0) {
            ArrayDeque<Flip> flips = FLIPS.computeIfAbsent(shipId, ignored -> new ArrayDeque<>());
            synchronized (flips) {
                flips.addLast(new Flip(flipTick, mapping));
                while (flips.size() > 1
                    && flipTick - flips.peekFirst().tick() > FLIP_RETENTION_TICKS) {
                    flips.removeFirst();
                }
            }
        }

        if (sourceLevel != null) {
            openWindow(shipId, mapping, portal, sourceLevel.dimension());
        } else {
            WINDOWS.remove(shipId);
        }
    }

    /**
     * WATERMARKED REBASE of the interpolator buffer.
     *
     * <p>Every snapshot the client still holds was produced either before or after the flip.
     * The ones from before are expressed in the source chart and must be mapped so the delayed
     * timeline runs continuously through the portal. The ones from after are ALREADY in the
     * destination chart; mapping them a second time is what threw them a full portal delta away
     * and produced the periodic vertical beating at speed.
     *
     * <p>Nothing is discarded. Dropping the pre-flip tail would leave the interpolator with a
     * single point and no motion to interpolate -- the exit would read as a teleport again.
     */
    private static void rebaseBuffer(
        ClientSubLevel sub, PortalMapping mapping, @Nullable Crossing crossing, int flipTick
    ) {
        SubLevelSnapshotInterpolator interpolator = sub.getInterpolator();
        if (interpolator == null) return;
        List<SubLevelSnapshotInterpolator.Snapshot> buffer = interpolator.buffer;

        synchronized (buffer) {
            for (int i = 0; i < buffer.size(); i++) {
                SubLevelSnapshotInterpolator.Snapshot snapshot = buffer.get(i);
                Pose3d pose = new Pose3d(snapshot.pose());
                Pose3d rebased;
                if (flipTick < 0) {
                    // No descriptor: behave exactly as before rather than guess.
                    rebased = mapping.mapPose(pose);
                } else if (snapshot.gameTick() < flipTick) {
                    rebased = mapping.mapPose(pose);
                } else if (snapshot.gameTick() > flipTick) {
                    continue; // already destination-frame
                } else {
                    // THE FLIP TICK ITSELF is genuinely ambiguous: whether this tick's snapshot
                    // was published before or after the flip depends on the order of two
                    // subsystems inside one server tick, which is not something to hard-code.
                    // Both candidates are known and the server told us where the body ended up,
                    // so pick the one that agrees with it. Order-independent by construction.
                    Pose3d mapped = mapping.mapPose(new Pose3d(snapshot.pose()));
                    rebased = crossing == null
                        || distanceSq(mapped, crossing.finalPose())
                            <= distanceSq(pose, crossing.finalPose())
                        ? mapped : pose;
                }
                buffer.set(i, new SubLevelSnapshotInterpolator.Snapshot(
                    snapshot.gameTick(), rebased));
            }

            // DEGENERATE CASE ONLY: nothing in the buffer predates the flip, so there is no
            // "before" pose to interpolate from and the exit would be a single point. Seed point
            // A -- the exit rectangle itself -- so the very next segment is literally
            // "portal plane -> rehome point". With a normally populated buffer the mapped
            // previous tick already says this, only more accurately, so leave it alone.
            if (crossing != null && flipTick > 0 && !hasSnapshotBefore(buffer, flipTick)) {
                insertSorted(buffer, new SubLevelSnapshotInterpolator.Snapshot(
                    flipTick - 1, new Pose3d(crossing.exitPlanePose())));
            }
        }
    }

    private static boolean hasSnapshotBefore(
        List<SubLevelSnapshotInterpolator.Snapshot> buffer, int flipTick
    ) {
        for (SubLevelSnapshotInterpolator.Snapshot snapshot : buffer) {
            if (snapshot.gameTick() < flipTick && snapshot.gameTick() >= flipTick - 2) return true;
        }
        return false;
    }

    /** The buffer must stay ordered by tick; the interpolator walks it as a timeline. */
    private static void insertSorted(
        List<SubLevelSnapshotInterpolator.Snapshot> buffer,
        SubLevelSnapshotInterpolator.Snapshot snapshot
    ) {
        int index = 0;
        while (index < buffer.size() && buffer.get(index).gameTick() < snapshot.gameTick()) index++;
        buffer.add(index, snapshot);
    }

    private static double distanceSq(Pose3dc a, Pose3dc b) {
        double dx = a.position().x() - b.position().x();
        double dy = a.position().y() - b.position().y();
        double dz = a.position().z() - b.position().z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Expresses a snapshot in the chart the client currently holds for this body.
     *
     * <p>Snapshots are delivered over UDP, so one produced before a flip can be handed to the
     * client after the flip has already been applied. Its pose is source-frame while the buffer
     * is destination-frame; inserting it as-is puts a pose from the wrong chart into the
     * timeline and the body lurches back through the portal for a frame. Applying every flip
     * newer than the snapshot's own tick, in order, is exactly the correction.
     */
    public static Pose3dc rebaseIncomingSnapshot(UUID shipId, int gameTick, Pose3dc pose) {
        ArrayDeque<Flip> flips = FLIPS.get(shipId);
        if (flips == null) return pose;
        // Copy under the lock, map outside it. Walking the live deque while the client thread
        // appends a flip is what produced impossible poses on this exact path.
        Flip[] pending;
        synchronized (flips) {
            if (flips.isEmpty()) return pose;
            pending = flips.toArray(new Flip[0]);
        }
        Pose3d mapped = null;
        for (Flip flip : pending) {
            if (flip.tick() <= gameTick) continue;
            mapped = flip.mapping().mapPose(mapped == null ? new Pose3d(pose) : mapped);
        }
        return mapped == null ? pose : mapped;
    }

    private static void openWindow(
        UUID shipId, PortalMapping mapping, @Nullable Portal portal, ResourceKey<Level> sourceDim
    ) {
        Vec3 sourceOrigin = mapping.origin();
        Vec3 sourceNormal = mapping.sourceNormal();

        // Source rectangle: the portal normal points at the source side by IP convention, and
        // the kept half-space is n*(p - pos) > 0 -- so this plane keeps precisely the part of
        // the body that has not come out of the portal yet.
        Plane keepSource = new Plane(sourceOrigin, sourceNormal);

        // Destination rectangle: P(portal origin) IS the portal destination, and the kept half
        // flips through the portal, n_dest = -R(n_src). This keeps the part already out.
        Vector3d rotated = new Vector3d(sourceNormal.x, sourceNormal.y, sourceNormal.z);
        mapping.rotation().transform(rotated);
        Plane keepDest = new Plane(
            mapping.destination(), new Vec3(-rotated.x, -rotated.y, -rotated.z));

        WINDOWS.put(shipId, new Window(mapping, portal, sourceDim, keepDest, keepSource));
    }

    /**
     * The straddle window of a body that has just flipped, or {@code null}.
     *
     * <p>Decided from the RENDERED pose, so it stays open for exactly as long as the body
     * visually straddles the exit rectangle -- however many frames the interpolator takes,
     * whatever the speed, and whether or not a straddle session ever existed. No visibility
     * latch, no prediction, no session lookup: those are what made the far half disappear.
     */
    @Nullable
    public static ExitWindow window(@Nullable ClientSubLevel sub) {
        if (sub == null) return null;
        Window window = WINDOWS.get(sub.getUniqueId());
        if (window == null) return null;

        if (sub.isRemoved()
            || System.currentTimeMillis() - window.openedAtMs > WINDOW_MAX_AGE_MS) {
            WINDOWS.remove(sub.getUniqueId());
            return null;
        }

        Pose3d renderPose = new Pose3d(sub.renderPose());
        if (fullyPast(sub, renderPose, window.keepDest)) {
            // The whole body is out, by a clear margin. Nothing left to project back.
            WINDOWS.remove(sub.getUniqueId());
            return null;
        }

        // The far half is drawn in the SOURCE chart, so it needs the render pose expressed
        // there. P is an isometry, so P-inverse of the interpolated pose is the same rigid body
        // seen from the other side of the aperture -- the two halves meet on the rectangle by
        // construction, with no seam and no double-drawn slab.
        Pose3d sourcePose = window.mapping.mapPoseInverse(renderPose);
        return new ExitWindow(
            window.portal, window.sourceDim, window.keepDest, sourcePose, window.keepSource);
    }

    /**
     * Whether every corner of the body's oriented box is past {@code plane}'s kept side by the
     * visual clearance. Corner-exact rather than centre-based: a long thin body is still
     * straddling long after its centre is through, and cutting it early is what produced a
     * visibly truncated far half.
     */
    private static boolean fullyPast(ClientSubLevel sub, Pose3d pose, Plane plane) {
        var bounds = sub.getPlot().getBoundingBox();
        Vec3 pos = plane.pos();
        Vec3 normal = plane.normal();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            Vec3 point = pose.transformPosition(new Vec3(
                x == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                y == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
            if (point.subtract(pos).dot(normal) <= EXIT_VISUAL_CLEARANCE) return false;
        }
        return true;
    }

    /**
     * A body mid-exit, ready to render.
     *
     * @param portal     the crossed portal; may be null if the entity could not be resolved,
     *                   in which case only the native clip half can be installed
     * @param sourceDim  the chart the far half must be projected into
     * @param keepDest   destination-frame plane keeping the half already out
     * @param sourcePose the body's rendered pose expressed in the source chart
     * @param keepSource source-frame plane keeping the half not yet out
     */
    public record ExitWindow(
        @Nullable Portal portal,
        ResourceKey<Level> sourceDim,
        Plane keepDest,
        Pose3d sourcePose,
        Plane keepSource
    ) {}

    /** Every body currently mid-exit whose far half belongs in {@code dim}. */
    public static List<UUID> shipsProjectingInto(ResourceKey<Level> dim) {
        if (WINDOWS.isEmpty()) return List.of();
        List<UUID> out = null;
        for (Map.Entry<UUID, Window> entry : WINDOWS.entrySet()) {
            if (!entry.getValue().sourceDim.equals(dim)) continue;
            if (out == null) out = new ArrayList<>(2);
            out.add(entry.getKey());
        }
        return out == null ? List.of() : out;
    }

    /** Body despawned, resynced, or left the world: drop its visual state. */
    public static void clear(UUID shipId) {
        FLIPS.remove(shipId);
        WINDOWS.remove(shipId);
    }

    public static void clearAll() {
        FLIPS.clear();
        WINDOWS.clear();
    }
}
