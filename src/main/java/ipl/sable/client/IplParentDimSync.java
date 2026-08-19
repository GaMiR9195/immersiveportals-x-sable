package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import ipl.sable.mixin.client.IplClientSubLevelRenderPoseAccessor;
import ipl.sable.mixin.client.IplSnapshotInterpolatorAccessor;
import ipl.sable.mixin.client.IplSubLevelLastPoseAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Client receiver for the hosted sub-level parent-dim stamp, invoked via IP's
 * {@code McRemoteProcedureCall} right after each full sync of a hosted sub-level
 * (see {@code SableCrossDimTrackingMixin}'s bootstrap).
 *
 * <p>The client-side {@code ClientSubLevel} is allocated in the sublevels-dim container by
 * the redirected start-tracking packet; its duck {@code parentLevel} defaults to the hosting
 * level. This call points it at the actual parent {@code ClientLevel} so the renderer and
 * client-side collision know which dimension the airship appears in.
 *
 * <p>It also owns the client half of a portal crossing. See
 * {@code docs/portal-aware-interpolation.md} for the model; the short version is that there
 * is exactly ONE canonical frame at any instant -- the frame the client is currently drawing
 * in -- and a portal crossing is a change of chart, not a change of motion:
 *
 * <ul>
 *   <li>While a handoff is queued, destination-frame snapshots are mapped BACK into the
 *       current client frame ({@link #mapPendingDestinationSnapshotBack}), so the body keeps
 *       flying straight through the doorway and overshoots it exactly as the server did.</li>
 *   <li>When the handoff commits, the retained history is mapped FORWARD through the same
 *       isometry. A portal isometry commutes with lerp/slerp, so the sampled curve is
 *       identical -- only re-expressed. No ticks are added, none are dropped, and the
 *       interpolation window stays Sable's original 6 ticks.</li>
 * </ul>
 */
public final class IplParentDimSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    /** RPC delivery can precede the redirected full-sync that creates the client sub-level. */
    private static final Map<UUID, java.util.ArrayDeque<PendingHandoff>> PENDING_HANDOFFS = new HashMap<>();

    /** Parent stamps that arrived before their client sub-level was created (retried per tick). */
    private static final Map<UUID, PendingParentStamp> PENDING_PARENT_STAMPS = new HashMap<>();

    /**
     * A queued handoff is applied unconditionally after this long, even without a client-side
     * exit proof. The server has already rehomed the body, so staying in the source frame
     * forever is strictly worse than a single mapped frame switch. 1000 ms is ~20 ticks --
     * well past Sable's 6-tick interpolation window -- so this never races a healthy crossing
     * and only fires when the aperture proof is genuinely never going to arrive (portal entity
     * out of client render range, sweep recorded on a different portal, etc.).
     */
    private static final long HANDOFF_FORCE_MS = 1_000L;

    /**
     * Pre-allocation handoffs are consumed by the parent stamp that follows them. If that
     * stamp never arrives, the entry has to expire or it leaks for the whole session and keeps
     * mapping every later snapshot back through a portal nobody crossed. Same horizon
     * {@code PENDING_PARENT_STAMPS} already used.
     */
    private static final long ALLOCATION_WAIT_TIMEOUT_MS = 30_000L;

    private record PendingParentStamp(String parentDimId, long queuedAtMs) {}

    private IplParentDimSync() {}

    private record PendingHandoff(
        String parentDimId, String portalTransform, String portalNbtB64,
        boolean awaitingClientAllocation, long queuedAtMs
    ) {}

    private static PendingHandoff pendingHandoff(
        String parentDimId, String portalTransform, String portalNbtB64, boolean awaitingClientAllocation
    ) {
        return new PendingHandoff(parentDimId, portalTransform, portalNbtB64,
            awaitingClientAllocation, System.currentTimeMillis());
    }

    private static long ipl$lastDiagMs = 0;

    /**
     * Round-trip bring-up diagnostic: per hosted client ship, the exact links that can
     * die independently — parent duck, render pose, session store, portal resolution.
     * 5s cadence; remove after the declarative-straddle stack stabilizes.
     */
    public static void clientHeartbeat() {
        long now = System.currentTimeMillis();
        if (now - ipl$lastDiagMs < 5000) return;
        ipl$lastDiagMs = now;

        SubLevelContainer container = IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return;
        boolean sawHosted = false;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (!(sub instanceof ClientSubLevel clientSub) || sub.isRemoved()) continue;
            sawHosted = true;
            Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(sub);
            var pos = clientSub.renderPose().position();
            LOG.info("[IPL-CLIENT-DIAG] ship={} parent={} pose=({},{},{}) portal={}",
                sub.getUniqueId(),
                parent == null ? "NULL" : parent.dimension().location(),
                String.format("%.1f", pos.x()), String.format("%.1f", pos.y()),
                String.format("%.1f", pos.z()),
                IplStraddleSessionStore.debugPortalKind(clientSub));
        }
        // A hosted body exists, so Sable's isSingleBlock() has certainly been asked about it
        // by now. If our routing injector never fired, the method was renamed and one-block
        // bodies are silently back on the eye-space path. Say so once instead of leaving a
        // camera-dependent triangular cut to be rediscovered by eye.
        IplHostedRenderRouting.verifyRoutingHook(sawHosted);
    }

    /** Retries handoffs that arrived before their client sub-level was created. */
    public static void applyPendingHandoffs() {
        if (!PENDING_HANDOFFS.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>>> iterator =
                PENDING_HANDOFFS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>> entry = iterator.next();
                java.util.ArrayDeque<PendingHandoff> pending = entry.getValue();
                try {
                    if (pending.isEmpty()) {
                        iterator.remove();
                        continue;
                    }
                    // A fresh destination full-sync already carries destination-frame poses.
                    // Wait for its parent stamp to consume this queue instead of mapping those
                    // initial poses through the portal a second time.
                    if (pending.peekFirst().awaitingClientAllocation()) {
                        if (now - pending.peekFirst().queuedAtMs() > ALLOCATION_WAIT_TIMEOUT_MS) {
                            IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                            iterator.remove();
                            LOG.debug("[IPL-PARENT-SYNC] expired pre-allocation handoff queue for {}",
                                entry.getKey());
                        }
                        continue;
                    }
                    // A client can receive several ordered parent flips before the redirected
                    // full-sync creates its hosted sub-level. Apply every transform in order:
                    // keeping only the latest transform maps an original-frame pose through a
                    // later portal and breaks portal chains/self-recursive portals.
                    while (!pending.isEmpty()) {
                        PendingHandoff next = pending.peekFirst();
                        int queuedBefore = pending.size();
                        boolean force = now - next.queuedAtMs() > HANDOFF_FORCE_MS;
                        RemoteCallables.beginHandoffVisual(entry.getKey(), next);
                        if (!RemoteCallables.applyHandoffWhenVisuallyClear(entry.getKey(), next, force)) {
                            break;
                        }
                        // applyHandoff() dequeues the head itself, under the interpolator's
                        // monitor, so the map-back chain and the mapped history can never
                        // disagree for even one snapshot. Guard against a future refactor
                        // reporting success without consuming the head and spinning here.
                        if (pending.size() == queuedBefore) {
                            LOG.error("[IPL-PARENT-SYNC] handoff for {} reported applied but stayed queued",
                                entry.getKey());
                            pending.removeFirst();
                        }
                    }
                    if (pending.isEmpty()) iterator.remove();
                } catch (Throwable t) {
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove();
                    LOG.error("[IPL-PARENT-SYNC] failed deferred handoff for {}", entry.getKey(), t);
                }
            }
        }
        // Parent stamps must run after handoffs. A stamp only changes ownership; applying it
        // first would expose a source-frame delayed pose in its destination world for one tick.
        applyPendingParentStamps();
    }

    /**
     * While a visual handoff waits for delayed source geometry to exit, incoming server
     * snapshots are already in the destination frame. Express them back in the current client
     * frame so Sable never interpolates a source pose directly toward a destination coordinate.
     *
     * <p>This is what makes the overshoot look right rather than merely tolerable: the mapped
     * image of the destination trajectory is the exact continuation of the source trajectory
     * through the doorway, so the body keeps its velocity, crosses the portal plane at the
     * same sub-tick instant the server did, and the clip does the rest.
     */
    public static Pose3dc mapPendingDestinationSnapshotBack(UUID subLevelId, Pose3dc snapshot) {
        java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
        if (pending == null || pending.isEmpty() || pending.peekFirst().awaitingClientAllocation()) {
            return snapshot;
        }
        Pose3d mapped = new Pose3d(snapshot);
        for (java.util.Iterator<PendingHandoff> it = pending.descendingIterator(); it.hasNext();) {
            mapped = RemoteCallables.PortalMapping.decode(it.next().portalTransform())
                .mapPoseInverse(mapped);
        }
        return mapped;
    }

    /**
     * Leaves the map-back chain for {@code subLevelId}. Called from inside
     * {@code applyHandoff}, while the interpolator buffer monitor is held, so a snapshot
     * arriving off-thread is either fully mapped back (still queued) or not mapped at all
     * (already committed) -- never one frame out of step with the buffer it lands in.
     *
     * <p>Only the deque is touched; the map entry itself is reaped by
     * {@link #applyPendingHandoffs()} through its iterator, so this can be called during that
     * iteration without a {@code ConcurrentModificationException}.
     */
    private static void dropQueuedHead(UUID subLevelId) {
        java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
        if (pending != null) pending.pollFirst();
    }

    /** Retries parent stamps that raced their StartTracking allocation. */
    private static void applyPendingParentStamps() {
        if (PENDING_PARENT_STAMPS.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingParentStamp>> iterator = PENDING_PARENT_STAMPS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingParentStamp> entry = iterator.next();
            try {
                if (now - entry.getValue().queuedAtMs() > ALLOCATION_WAIT_TIMEOUT_MS) {
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove(); // ship never materialized client-side — stop retrying
                    continue;
                }
                SubLevel subLevel = RemoteCallables.findHostedSubLevel(
                    entry.getKey().toString(), entry.getValue().parentDimId());
                if (subLevel != null) {
                    RemoteCallables.setParentInternal(subLevel, entry.getValue().parentDimId());
                    LOG.debug("[IPL-PARENT-SYNC] deferred parent stamp applied: {} parent={}",
                        entry.getKey(), entry.getValue().parentDimId());
                    iterator.remove();
                }
            } catch (Throwable t) {
                iterator.remove();
                LOG.error("[IPL-PARENT-SYNC] failed deferred parent stamp for {}", entry.getKey(), t);
            }
        }
    }

    public static final class RemoteCallables {

        public static void setParent(String subLevelUuid, String parentDimId) {
            try {
                UUID subLevelId = UUID.fromString(subLevelUuid);
                SubLevel subLevel = findHostedSubLevel(subLevelUuid, parentDimId);
                if (subLevel == null) {
                    // The RPC raced the StartTracking allocation. Without a retry the ship
                    // keeps a null client parent and the hosted render gather filters it out
                    // FOREVER — "the ship exists (physics, collision) but is invisible".
                    // Single-block ships (swivel tops, shattered blocks, rope connectors)
                    // never emit further updates that could heal it, so they stayed gone.
                    PENDING_PARENT_STAMPS.put(subLevelId,
                        new PendingParentStamp(parentDimId, System.currentTimeMillis()));
                    return;
                }
                if (discardPreAllocationHandoffs(subLevelId, parentDimId)) {
                    // This client did not have a source-frame body when the handoff arrived.
                    // Its just-created full sync is already in the final destination frame.
                    setParentInternal(subLevel, parentDimId);
                    IplStraddleSessionStore.clearHandoffVisual(subLevelId);
                    PENDING_PARENT_STAMPS.remove(subLevelId);
                    return;
                }
                if (PENDING_HANDOFFS.containsKey(subLevelId)) {
                    // A parent stamp can race the full-sync that a queued handoff waits for.
                    // Keep it behind the FIFO so ownership never becomes visible before its
                    // delayed render timeline has been mapped into that frame.
                    PENDING_PARENT_STAMPS.put(subLevelId,
                        new PendingParentStamp(parentDimId, System.currentTimeMillis()));
                    return;
                }
                setParentInternal(subLevel, parentDimId);
                PENDING_PARENT_STAMPS.remove(subLevelId);

                LOG.debug("[IPL-PARENT-SYNC] sub-level {} parent={} (client)",
                    subLevelUuid, parentDimId);
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to apply parent stamp for {}", subLevelUuid, t);
            }
        }

        /**
         * Atomically switches an already-tracked ship from its source frame to its
         * destination frame. Unlike a normal parent stamp, this also re-expresses Sable's
         * interpolation timeline in destination space. The server pose is deliberately not
         * used as a baseline: it is ahead of Sable's interpolation delay and would produce a
         * visible forward jump on every smooth crossing.
         */
        public static void handoff(
            String subLevelUuid, String parentDimId, String portalTransform, String portalNbtB64
        ) {
            try {
                UUID subLevelId = UUID.fromString(subLevelUuid);
                // Do not let a later RPC bypass an earlier one that raced client creation.
                // The body pose is still in the first portal's source frame until the FIFO
                // is drained, so every portal transform must compose in wire order.
                java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
                if (pending != null && !pending.isEmpty()) {
                    pending.addLast(pendingHandoff(parentDimId, portalTransform, portalNbtB64, false));
                    return;
                }
                SubLevel subLevel = findHostedSubLevel(subLevelUuid, parentDimId);
                if (!(subLevel instanceof ClientSubLevel)) {
                    // No source-frame client object exists. Its eventual full sync is already
                    // destination-frame, so never create a source-side visual tail for it.
                    PENDING_HANDOFFS.computeIfAbsent(subLevelId, ignored -> new java.util.ArrayDeque<>())
                        .addLast(pendingHandoff(parentDimId, portalTransform, portalNbtB64, true));
                    return;
                }
                PendingHandoff handoff = pendingHandoff(
                    parentDimId, portalTransform, portalNbtB64, false);
                if (!applyHandoffWhenVisuallyClear(subLevelId, handoff, false)) {
                    PENDING_HANDOFFS.computeIfAbsent(subLevelId, ignored -> new java.util.ArrayDeque<>())
                        .addLast(handoff);
                    beginHandoffVisual(subLevelId, handoff);
                }
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to hand off hosted sub-level {}", subLevelUuid, t);
            }
        }

        /**
         * Keep a server-completed handoff queued until Sable's delayed render volume has
         * fully cleared the source plane. Committing earlier turns the still-visible source
         * half into a destination pose, producing the end-edge cut and a small rehome jerk.
         *
         * @param force apply without a client-side exit proof (see {@code HANDOFF_FORCE_MS}).
         *              A queued handoff must not be able to stall forever: the server has
         *              already moved the body, and the source frame is the one that is wrong.
         */
        private static boolean applyHandoffWhenVisuallyClear(
            UUID subLevelId, PendingHandoff handoff, boolean force
        ) {
            String parentDimId = handoff.parentDimId();
            SubLevel subLevel = findHostedSubLevel(subLevelId.toString(), parentDimId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) {
                if (!force) return false;
                // The body disappeared client-side while its handoff was queued, so no proof
                // can ever be produced. Drop the entry instead of mapping every future
                // snapshot back through a portal that is no longer relevant to anything.
                dropQueuedHead(subLevelId);
                IplStraddleSessionStore.clearAllVisualState(subLevelId);
                LOG.warn("[IPL-PARENT-SYNC] dropped queued handoff for vanished client sub-level {}",
                    subLevelId);
                return true;
            }
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            if (force) {
                LOG.warn("[IPL-PARENT-SYNC] forcing handoff for {} after {} ms with no exit proof",
                    subLevelId, HANDOFF_FORCE_MS);
            } else {
                if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(clientSubLevel)
                    instanceof ClientLevel sourceLevel)) return false;
                qouteall.imm_ptl.core.portal.Portal portal = IplStraddleSessionStore.resolveHandoffPortal(
                    mapping.portalId(), handoff.portalNbtB64(), sourceLevel);
                if (portal == null
                    || !IplClientVisualTransitLatch.hasForwardApertureSweep(clientSubLevel, portal)) {
                    return false;
                }
                if (!mapping.hasFullyClearedSourcePlane(clientSubLevel)) return false;
            }
            applyHandoff(subLevelId, parentDimId, mapping);
            IplStraddleSessionStore.clearHandoffVisual(subLevelId);
            IplStraddleSessionStore.releaseHandoffPortal();
            return true;
        }

        /** Only the FIFO head receives a render tail; later recursive handoffs wait. */
        private static void beginHandoffVisual(UUID subLevelId, PendingHandoff handoff) {
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            IplStraddleSessionStore.beginHandoffVisual(
                subLevelId, mapping.portalId(), handoff.portalNbtB64());
        }

        private static boolean discardPreAllocationHandoffs(UUID subLevelId, String parentDimId) {
            java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
            if (pending == null || pending.isEmpty() || !pending.peekFirst().awaitingClientAllocation()) {
                return false;
            }
            PendingHandoff last = pending.peekLast();
            if (!last.parentDimId().equals(parentDimId)) return false;
            PENDING_HANDOFFS.remove(subLevelId);
            return true;
        }

        /**
         * Commits the frame switch: the whole client-side timeline is re-expressed in
         * destination space, ownership flips, and cached render state is dropped.
         *
         * <p><b>Why re-expressing beats reseeding.</b> A portal handoff is a change of chart,
         * not a change of motion. The portal mapping {@code P} is an isometry composed with a
         * uniform scale, so it commutes with the interpolation Sable performs:
         * {@code P(lerp(a, b, u)) == lerp(P(a), P(b), u)} for positions and
         * {@code P(slerp(qa, qb, u)) == slerp(P(qa), P(qb), u)} for orientations, because
         * {@code P} acts on the left. Mapping every retained snapshot therefore yields the
         * SAME sampled curve, merely written in the destination chart. Nothing about the
         * timing changes: no snapshot is added or removed, {@code gameTick} values are
         * untouched, and {@code tick(backTick)} keeps its original
         * {@code bufferStartTime = backTick - 6} window. "6 ticks in the original" stays
         * exactly 6 ticks here.
         *
         * <p>The previous implementation cleared the buffer and reseeded two synthetic
         * endpoints (doorway pose, rehome pose). That was the source of the remaining
         * artefacts, and it was unfixable in place:
         *
         * <ul>
         *   <li>{@code getSampleAt} needs a snapshot at or before {@code backTick}. After a
         *       clear, the oldest snapshot is the crossing tick itself, which is ~6 ticks in
         *       the future relative to the delayed render clock, so {@code before} stayed
         *       null for the whole window and the body sat FROZEN on the doorway before
         *       jerking to the rehome point.</li>
         *   <li>Dead reckoning needs {@code beforeBefore}; a two-entry buffer cannot provide
         *       it, so a single dropped packet right after a crossing produced a visible
         *       stall exactly where motion is most conspicuous.</li>
         *   <li>The synthetic "doorway" endpoint had to be recomputed client-side
         *       ({@code projectOntoExitPlane}), duplicating a decision the server already
         *       makes in {@code PortalCrossingDetector.projectOntoExitPlane}. Two independent
         *       implementations of the same clamp is one too many; the client copy is gone.</li>
         * </ul>
         *
         * <p>Because incoming snapshots were already being mapped BACK into the source frame
         * while the handoff was queued, the retained history and the newest snapshots are in
         * one single frame at this point. Mapping that single frame forward is therefore
         * total and unambiguous -- there is no mixed-frame buffer to reason about, and no
         * seam tick to invent.
         */
        private static void applyHandoff(
            UUID subLevelId, String parentDimId, PortalMapping mapping
        ) {
            SubLevel subLevel = findHostedSubLevel(subLevelId.toString(), parentDimId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) return;
            // The crossing PROOF must outlive the handoff. clear() also drops
            // FORWARD_SWEEPS, and hasForwardApertureSweep() consults exactly that first;
            // without it the visual frame switch has to be re-proven from poses that are
            // already destination-side, which is impossible by construction. The crossing
            // really happened, so the proof stays and only the stale prediction goes.
            IplClientVisualTransitLatch.clearPredictionKeepingProof(subLevelId);

            SubLevelSnapshotInterpolator interpolator = clientSubLevel.getInterpolator();
            // One monitor for both halves of the switch. receiveSnapshot() synchronizes on
            // the buffer, and the incoming map-back does too, so leaving the map-back chain
            // and rebasing the history are atomic with respect to packet arrival.
            synchronized (interpolator.buffer) {
                dropQueuedHead(subLevelId);
                rebaseIntoDestinationFrame(clientSubLevel, interpolator, mapping);
            }
            clientSubLevel.forceUpdateBounds();

            // Do not expose the new parent until every client pose is in destination
            // space. A pass already in progress may otherwise render a stale source
            // projection as well as the newly native destination sub-level.
            setParentInternal(clientSubLevel, parentDimId);
            // The retired split only bridges server session-end to this exact mapped pose.
            // Once the parent frame becomes visible, retaining its old portal would clip the
            // native destination draw with a source-frame plane.
            IplStraddleSessionStore.clearRetiredForHandoff(subLevelId);
            // Staff drag frame changes ride the dedicated grab-chain rebase RPC (ordered
            // after this handoff on the same channel); nothing staff-related to do here.
            // Straddle parity is server-synced state now (IplStraddleSessionStore); the
            // "crossed" session-end snapshot precedes this handoff on the ordered channel,
            // so there is no client-side latch left to clear here.
            IplStraddleRenderCache.invalidateActivePasses();
            // Sable caches renderPose() per partial tick; force the next frame to rebuild it
            // from the rebased timeline instead of reusing the source-frame result.
            ((IplClientSubLevelRenderPoseAccessor) clientSubLevel)
                .ipl$setLastRenderPosePartialTick(-1.0f);
            var pos = clientSubLevel.logicalPose().position();
            LOG.debug("[IPL-PARENT-SYNC] handoff applied for {} -> parent {} pose=({},{},{})",
                subLevelId, parentDimId,
                String.format("%.1f", pos.x()),
                String.format("%.1f", pos.y()),
                String.format("%.1f", pos.z()));
        }

        /**
         * Re-expresses every client-side pose of {@code clientSubLevel} in the destination
         * frame: the retained snapshot history, the interpolator's running sample, and the
         * {@code lastPose}/{@code logicalPose} pair {@code renderPose(partialTick)} lerps
         * between.
         *
         * <p>Sable's {@code receiveSnapshot} stores poses BY REFERENCE, so a snapshot pose is
         * mutated in place when it is a mutable {@code Pose3d} and only replaced when it is
         * not. The identity set is not an optimization: the same instance can legitimately be
         * reachable twice (a buffered pose that is also the running sample), and mapping one
         * object twice would put it a full portal offset away.
         *
         * <p>Must be called with the interpolator buffer monitor held.
         */
        private static void rebaseIntoDestinationFrame(
            ClientSubLevel clientSubLevel,
            SubLevelSnapshotInterpolator interpolator,
            PortalMapping mapping
        ) {
            java.util.Set<Pose3d> alreadyMapped =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

            for (int i = 0; i < interpolator.buffer.size(); i++) {
                SubLevelSnapshotInterpolator.Snapshot snapshot = interpolator.buffer.get(i);
                Pose3dc pose = snapshot.pose();
                if (pose == null) continue;
                if (pose instanceof Pose3d mutable) {
                    remapInPlace(mutable, mapping, alreadyMapped);
                } else {
                    interpolator.buffer.set(i, new SubLevelSnapshotInterpolator.Snapshot(
                        snapshot.gameTick(), mapping.mapPose(new Pose3d(pose))));
                }
            }

            // Cast through Object: the accessor interface is mixed into Sable's class at
            // runtime, so the compiler must not be asked to relate the two types.
            remapInPlace(((IplSnapshotInterpolatorAccessor) (Object) interpolator).ipl$getRunningSnapshot(),
                mapping, alreadyMapped);
            remapInPlace(((IplSubLevelLastPoseAccessor) clientSubLevel).ipl$getLastPose(),
                mapping, alreadyMapped);
            remapInPlace(clientSubLevel.logicalPose(), mapping, alreadyMapped);
        }

        private static void remapInPlace(
            Pose3d pose, PortalMapping mapping, java.util.Set<Pose3d> alreadyMapped
        ) {
            if (pose == null || !alreadyMapped.add(pose)) return;
            pose.set(mapping.mapPose(new Pose3d(pose)));
        }

        /**
         * IP only sends portal entities that are relevant to the client camera. A tracked
         * construction can cross a distant portal in one tick, so carry the portal transform
         * in the handoff itself rather than leaving its destination-frame switch dependent on
         * that entity being in the client's render list.
         */
        public record PortalMapping(
            net.minecraft.world.phys.Vec3 origin,
            net.minecraft.world.phys.Vec3 destination,
            Quaterniond rotation,
            double scale,
            net.minecraft.world.phys.Vec3 sourceNormal,
            UUID portalId
        ) {
            public static PortalMapping decode(String encoded) {
                String[] values = encoded.split(";", -1);
                if (values.length != 15) {
                    throw new IllegalArgumentException("invalid portal handoff transform");
                }
                double[] decoded = new double[14];
                for (int i = 0; i < decoded.length; i++) {
                    decoded[i] = Double.valueOf(values[i]);
                }
                return new PortalMapping(
                    new net.minecraft.world.phys.Vec3(decoded[0], decoded[1], decoded[2]),
                    new net.minecraft.world.phys.Vec3(decoded[3], decoded[4], decoded[5]),
                    new Quaterniond(decoded[6], decoded[7], decoded[8], decoded[9]), decoded[10],
                    new net.minecraft.world.phys.Vec3(decoded[11], decoded[12], decoded[13]),
                    UUID.fromString(values[14])
                );
            }

            Pose3d mapPose(Pose3d sourcePose) {
                Vector3d offset = new Vector3d(
                    sourcePose.position().x() - origin.x,
                    sourcePose.position().y() - origin.y,
                    sourcePose.position().z() - origin.z
                ).mul(scale);
                rotation.transform(offset);

                Pose3d destinationPose = new Pose3d(sourcePose);
                destinationPose.position().set(
                    destination.x + offset.x, destination.y + offset.y, destination.z + offset.z
                );
                destinationPose.orientation().set(new Quaterniond(rotation).mul(sourcePose.orientation()));
                return destinationPose;
            }

            public Pose3d mapPoseInverse(Pose3d destinationPose) {
                Vector3d offset = new Vector3d(
                    destinationPose.position().x() - destination.x,
                    destinationPose.position().y() - destination.y,
                    destinationPose.position().z() - destination.z
                );
                Quaterniond inverseRotation = new Quaterniond(rotation).invert();
                inverseRotation.transform(offset);
                offset.div(scale);

                Pose3d sourcePose = new Pose3d(destinationPose);
                sourcePose.position().set(
                    origin.x + offset.x, origin.y + offset.y, origin.z + offset.z
                );
                sourcePose.orientation().set(inverseRotation.mul(destinationPose.orientation()));
                return sourcePose;
            }

            boolean hasFullyClearedSourcePlane(ClientSubLevel sub) {
                var bounds = sub.getPlot().getBoundingBox();
                Pose3d pose = new Pose3d(sub.renderPose());
                Vec3 sourceToDest = sourceNormal.scale(-1.0);
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                    Vec3 point = pose.transformPosition(new Vec3(
                        x == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                        y == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                        z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
                    if (point.subtract(origin).dot(sourceToDest) < -1.0e-8) return false;
                }
                return true;
            }
        }

        private static ResourceKey<Level> parentKey(String parentDimId) {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(parentDimId));
        }

        private static SubLevel findHostedSubLevel(String subLevelUuid, String parentDimId) {
            ClientLevel hosting = ClientWorldLoader.getWorld(SableSubLevelDimension.SUBLEVELS);
            SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
            if (container == null) {
                LOG.warn("[IPL-PARENT-SYNC] sublevels client world has no container");
                return null;
            }

            SubLevel subLevel = container.getSubLevel(UUID.fromString(subLevelUuid));
            if (subLevel == null) {
                LOG.warn("[IPL-PARENT-SYNC] no hosted sub-level {} (parent {})",
                    subLevelUuid, parentDimId);
            }
            return subLevel;
        }

        static void setParentInternal(SubLevel subLevel, String parentDimId) {
            ClientLevel hosting = ClientWorldLoader.getWorld(SableSubLevelDimension.SUBLEVELS);
            ResourceKey<Level> parentKey = parentKey(parentDimId);
            ClientLevel parent = ClientWorldLoader.getWorld(parentKey);

            IplSubLevelDuck duck = (IplSubLevelDuck) subLevel;
            ClientLevel oldParent = duck.ipl$getParentLevel() instanceof ClientLevel old ? old : null;
            duck.ipl$setParentLevel(parent);
            duck.ipl$setHostingLevel(hosting);

            // Flywheel visuals live in the parent's visualization world — re-home them
            // with the flip so swivel bearings / throttle levers keep rendering after
            // a cross-portal transit.
            if (oldParent != parent) {
                ipl.sable.client.IplClientFlywheelReroute.onParentFlip(subLevel, oldParent, parent);
            }
        }
    }
}
