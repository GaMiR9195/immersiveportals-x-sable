package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.mixin.SubLevelAccessor;
import ipl.sable.mixin.client.ClientSubLevelAccessor;
import ipl.sable.mixin.client.SnapshotInterpolatorAccessor;
import ipl.sable.transit.IplGrabLink;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of server transits in the native Sable model.
 *
 * <ul>
 *   <li><b>Same-dimension flip</b> ({@code handoff} RPC): the body keeps its object and
 *       level; only its coordinate chart jumps through the portal. Every buffered client
 *       pose is rebased through the portal isometry once the delayed render stream has
 *       caught up with the server's flip tick, so interpolation never crosses the seam.</li>
 *   <li><b>Cross-dimension move</b> ({@code move} RPC, sent before Sable's stop-tracking
 *       packet): the old client object becomes a short-lived GHOST that keeps rendering
 *       through the crossing portal (source half + projection) until its successor in the
 *       destination level has render geometry; the successor stays hidden meanwhile, so
 *       the switch happens in one frame with neither a gap nor a double image. The successor
 *       continues the ghost (its poses and snapshot history), so the motion goes on without a
 *       skip or a change of pace.</li>
 * </ul>
 *
 * <p>All levels share one snapshot timeline and one interpolation clock
 * ({@code IplTimeline}, {@link IplSharedClock}): a history carried into a new object keeps its
 * stamps, and the new object shows exactly the pose the old one would have shown.
 */
public final class IplParentDimSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    private static final long GHOST_TIMEOUT_MS = 3000L;
    private static final int GHOST_READY_TICKS = 3;

    /** Same-dimension flips waiting for the delayed render stream, FIFO per body. */
    private static final Map<UUID, ArrayDeque<PendingHandoff>> PENDING_HANDOFFS = new HashMap<>();

    private static final Map<UUID, Long> FRAMES = new HashMap<>();

    /** Body id → the old client object of a cross-dimension move. */
    private static final Map<UUID, Ghost> GHOSTS = new HashMap<>();

    /** Stop-tracking packets of ghosts removed before they arrived — swallowed silently. */
    private static final Map<ResourceKey<Level>, Integer> EXPECTED_STOPS = new HashMap<>();

    private record PendingHandoff(
        String dimensionId, String portalTransform, String portalNbtB64,
        int firstDestinationTick, long destinationFrame, String inverseGrabLink
    ) {}

    private static final class Ghost {
        final ClientSubLevel sub;
        final ResourceKey<Level> toDim;
        final RemoteCallables.PortalMapping mapping;
        @Nullable final Portal surrogate;
        final long startMs;
        /** Frame the ghost still SHOWS (the one before the move); picks on it name this frame. */
        final long fromFrame;
        int readyTicks;
        boolean stopDeferred;

        Ghost(ClientSubLevel sub, ResourceKey<Level> toDim, RemoteCallables.PortalMapping mapping,
              @Nullable Portal surrogate, long startMs, long fromFrame) {
            this.sub = sub;
            this.toDim = toDim;
            this.mapping = mapping;
            this.surrogate = surrogate;
            this.startMs = startMs;
            this.fromFrame = fromFrame;
        }
    }

    private IplParentDimSync() {}

    public static long frame(UUID id) { return FRAMES.getOrDefault(id, 0L); }

    /**
     * Coordinate frame of what a pick of {@code sub} actually saw. A transit ghost keeps
     * showing the pre-move frame although the body's frame already advanced at the move RPC;
     * a pick on it must name that older frame so the server appends the move it missed.
     */
    public static long pickFrame(ClientSubLevel sub) {
        Ghost ghost = ghostOf(sub);
        return ghost != null ? ghost.fromFrame : frame(sub.getUniqueId());
    }

    /** Destination level of a transit ghost, or null when {@code sub} is not a ghost. */
    @Nullable
    public static ResourceKey<Level> ghostDestination(ClientSubLevel sub) {
        Ghost ghost = ghostOf(sub);
        return ghost != null ? ghost.toDim : null;
    }

    /**
     * A plot-local point of a transit ghost, expressed in the body's destination world: on the
     * successor once it exists (its plot may sit in another slot), else on the ghost's own pose
     * mapped through the crossing portal. Null when {@code sub} is not a ghost.
     */
    @Nullable
    public static Vec3 ghostPointInDestination(ClientSubLevel sub, Vec3 plotPoint, float partialTick) {
        Ghost ghost = ghostOf(sub);
        if (ghost == null) return null;
        ClientSubLevel successor = ghostSuccessor(sub);
        if (successor != null) {
            Vector3d shift = plotShift(sub, successor);
            return successor.renderPose(partialTick)
                .transformPosition(plotPoint.add(shift.x, shift.y, shift.z));
        }
        Pose3d mapped = ghost.mapping.mapPose(new Pose3d(sub.renderPose(partialTick)));
        return mapped.transformPosition(plotPoint);
    }

    private static void enterFrame(UUID id, long next) {
        long old = frame(id);
        FRAMES.put(id, next);
        if (old != next) IplStraddleSessionStore.enterFrame(id, next);
    }

    /** Object lifetime, not portal lifetime. Called on final removal and disconnect. */
    public static void forget(UUID id) {
        PENDING_HANDOFFS.remove(id);
        FRAMES.remove(id);
        GHOSTS.remove(id);
        IplStraddleSessionStore.clearAllVisualState(id);
    }

    public static void clear() {
        PENDING_HANDOFFS.clear();
        FRAMES.clear();
        GHOSTS.clear();
        EXPECTED_STOPS.clear();
        IplStraddleSessionStore.clear();
        IplGrabChainClient.clearAll();
        IplStraddleStaffPick.clearDragTargets();
    }

    public static boolean hasPendingHandoff(UUID id) {
        return PENDING_HANDOFFS.containsKey(id);
    }

    /** Rendering ends at the delayed client chart; motor commands end at the server chart. */
    public static List<IplGrabLink> visibleGrabChain(
        UUID id, long chainFrame, List<IplGrabLink> authoritative
    ) {
        var queue = PENDING_HANDOFFS.get(id);
        if (queue == null || queue.isEmpty()) return authoritative;
        var result = authoritative;
        for (var it = queue.descendingIterator(); it.hasNext();) {
            var handoff = it.next();
            if (handoff.destinationFrame() > chainFrame) continue;
            result = IplGrabLink.append(result, IplGrabLink.decode(handoff.inverseGrabLink()));
        }
        return result;
    }

    /** Client tick tail: drains same-dimension handoffs, then advances transit ghosts. */
    public static void applyPendingHandoffs() {
        if (!PENDING_HANDOFFS.isEmpty()) {
            Iterator<Map.Entry<UUID, ArrayDeque<PendingHandoff>>> iterator =
                PENDING_HANDOFFS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, ArrayDeque<PendingHandoff>> entry = iterator.next();
                ArrayDeque<PendingHandoff> pending = entry.getValue();
                try {
                    // Several ordered flips can queue before the delayed stream catches up;
                    // apply every transform in order (portal chains, self-recursive portals).
                    while (!pending.isEmpty()) {
                        PendingHandoff next = pending.peekFirst();
                        if (!RemoteCallables.applyHandoffWhenVisuallyClear(entry.getKey(), next, false)) break;
                        pending.removeFirst();
                    }
                    if (pending.isEmpty()) iterator.remove();
                } catch (RuntimeException e) {
                    // One broken body must not stall the others or crash the client tick.
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove();
                    LOG.error("[IPL-PARENT-SYNC] failed deferred handoff for {}", entry.getKey(), e);
                }
            }
        }
        tickGhosts();
    }

    /**
     * While a same-dimension handoff waits for delayed source geometry to exit, incoming
     * server snapshots are already in the destination chart. Express them back in the
     * current client chart so Sable never interpolates across the seam.
     */
    public static Pose3dc mapPendingDestinationSnapshotBack(UUID subLevelId, int gameTick, Pose3dc snapshot) {
        ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
        if (pending == null || pending.isEmpty()) return snapshot;
        Pose3d mapped = new Pose3d(snapshot);
        for (Iterator<PendingHandoff> it = pending.descendingIterator(); it.hasNext();) {
            PendingHandoff handoff = it.next();
            // Only packets at or after the captured tracking watermark belong to the new chart.
            if (gameTick >= handoff.firstDestinationTick()) {
                mapped = RemoteCallables.PortalMapping.decode(handoff.portalTransform()).mapPoseInverse(mapped);
            }
        }
        return mapped;
    }

    // ------------------------------------------------------------------ transit ghosts

    public static boolean hasGhosts() {
        return !GHOSTS.isEmpty();
    }

    public static boolean isGhost(ClientSubLevel sub) {
        return ghostOf(sub) != null;
    }

    /** The transit ghost whose old client object is {@code sub}, or null. */
    @Nullable
    private static Ghost ghostOf(@Nullable ClientSubLevel sub) {
        if (GHOSTS.isEmpty() || sub == null) return null;
        Ghost ghost = GHOSTS.get(sub.getUniqueId());
        return ghost != null && ghost.sub == sub ? ghost : null;
    }

    /** A moved body's new client object, hidden until the ghost hands over. */
    public static boolean isSuppressedSuccessor(ClientSubLevel sub) {
        if (GHOSTS.isEmpty() || sub == null) return false;
        Ghost ghost = GHOSTS.get(sub.getUniqueId());
        return ghost != null && ghost.sub != sub;
    }

    /** The crossing portal a ghost keeps rendering through (live entity preferred). */
    @Nullable
    public static Portal ghostPortalFor(ClientSubLevel sub) {
        Ghost ghost = ghostOf(sub);
        if (ghost == null) return null;
        Portal live = IplStraddleSessionStore.findLivePortal(sub.getLevel(), ghost.mapping.portalId());
        return live != null ? live : ghost.surrogate;
    }

    /** The successor a ghost hands over to, once Sable tracks it in the destination level. */
    @Nullable
    public static ClientSubLevel ghostSuccessor(ClientSubLevel sub) {
        Ghost ghost = ghostOf(sub);
        if (ghost == null) return null;
        ClientSubLevel successor = IplClientProjections.findClientSub(sub.getUniqueId(), ghost.toDim);
        return successor == sub ? null : successor;
    }

    /**
     * A ghost follows its successor's live pose, mapped back through the crossing portal. The
     * successor's plot may sit in another slot; its plot-local rotation point is shifted back.
     */
    @Nullable
    public static Pose3dc ghostRenderPose(ClientSubLevel sub) {
        if (GHOSTS.isEmpty() || sub == null) return null;
        Ghost ghost = GHOSTS.get(sub.getUniqueId());
        if (ghost == null || ghost.sub != sub) return null;
        ClientSubLevel successor = IplClientProjections.findClientSub(sub.getUniqueId(), ghost.toDim);
        if (successor == null || successor == sub) return null;
        Pose3d back = ghost.mapping.mapPoseInverse(new Pose3d(successor.renderPose()));
        back.rotationPoint().sub(plotShift(sub, successor));
        return back;
    }

    /** Offset of the successor's plot from the ghost's, in blocks: plot-local points move by it. */
    private static Vector3d plotShift(ClientSubLevel ghost, ClientSubLevel successor) {
        ChunkPos ghostPlot = ghost.getPlot().getChunkMin();
        ChunkPos successorPlot = successor.getPlot().getChunkMin();
        return new Vector3d((successorPlot.x - ghostPlot.x) * 16.0, 0.0, (successorPlot.z - ghostPlot.z) * 16.0);
    }

    /** Stop-tracking of a ghost: keep the object until its successor can be seen. */
    public static boolean deferStopTracking(Level level, int chunkX, int chunkZ) {
        if (GHOSTS.isEmpty() && EXPECTED_STOPS.isEmpty()) return false;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        SubLevel sub = container.getSubLevel(chunkX, chunkZ);
        if (sub == null) {
            Integer expected = EXPECTED_STOPS.get(level.dimension());
            if (expected == null) return false;
            if (expected <= 1) EXPECTED_STOPS.remove(level.dimension());
            else EXPECTED_STOPS.put(level.dimension(), expected - 1);
            return true;
        }
        if (!(sub instanceof ClientSubLevel client)) return false;
        Ghost ghost = GHOSTS.get(client.getUniqueId());
        if (ghost == null || ghost.sub != client) return false;
        ghost.stopDeferred = true;
        return true;
    }

    /** A start-tracking of {@code id} in a level that still holds its ghost replaces it. */
    public static void onStartTracking(Level level, UUID id) {
        Ghost ghost = GHOSTS.get(id);
        if (ghost != null && ghost.sub.getLevel() == level) finalizeGhost(id, ghost);
    }

    /**
     * The successor of a cross-dimension move, just created by Sable's start-tracking, continues
     * its ghost through the crossing portal: it takes the poses the ghost shows, and the ghost's
     * snapshots older than its own lead its buffer. Both levels sample with the one shared clock
     * and stamp on one timeline, so the body keeps its time and pace across the portal, whatever
     * else moves in either level.
     *
     * <p>The ghost follows the successor from the moment it exists ({@link #ghostRenderPose}).
     * On its own the successor would sit at the server's previous pose until its first tick and
     * then hold its first snapshot until the clock reached it: the body skipped ahead at the exit
     * and hung there.
     */
    public static void continueFromGhost(Level level, UUID id) {
        Ghost ghost = GHOSTS.get(id);
        if (ghost == null || !level.dimension().equals(ghost.toDim)) return;
        SubLevelContainer destination = SubLevelContainer.getContainer(level);
        if (destination == null || !(destination.getSubLevel(id) instanceof ClientSubLevel successor)) return;
        Vector3d shift = plotShift(ghost.sub, successor);
        // The local staff drag continues on the successor before the ghost can be removed.
        IplGrabChainClient.rebindDragToSuccessor(ghost.sub, successor, shift);
        continueHistory(ghost, successor, shift);
        Pose3d logical = ghost.mapping.mapPose(new Pose3d(ghost.sub.logicalPose()));
        Pose3d last = ghost.mapping.mapPose(new Pose3d(ghost.sub.lastPose()));
        logical.rotationPoint().add(shift);
        last.rotationPoint().add(shift);
        successor.logicalPose().set(logical);
        ((SubLevelAccessor) successor).ipl$getPreviousPose().set(last);
        successor.forceUpdateBounds();
        ((ClientSubLevelAccessor) successor).ipl$setLastRenderPosePartialTick(-1.0f);
    }

    /** The ghost's snapshots older than the successor's first one, mapped, lead its buffer. */
    private static void continueHistory(Ghost ghost, ClientSubLevel successor, Vector3d shift) {
        List<SubLevelSnapshotInterpolator.Snapshot> history = new ArrayList<>();
        SubLevelSnapshotInterpolator source = ghost.sub.getInterpolator();
        synchronized (source.buffer) {
            for (SubLevelSnapshotInterpolator.Snapshot snapshot : source.buffer) {
                Pose3d pose = ghost.mapping.mapPose(new Pose3d(snapshot.pose()));
                pose.rotationPoint().add(shift);
                history.add(new SubLevelSnapshotInterpolator.Snapshot(snapshot.gameTick(), pose));
            }
        }
        SubLevelSnapshotInterpolator target = successor.getInterpolator();
        synchronized (target.buffer) {
            if (!target.buffer.isEmpty()) {
                int first = target.buffer.getFirst().gameTick();
                history.removeIf(snapshot -> snapshot.gameTick() >= first);
            }
            target.buffer.addAll(0, history);
        }
    }

    /** {@code ClientSubLevel.onRemove}: forget per-body state only when no instance survives. */
    public static void onClientSubRemoved(ClientSubLevel sub) {
        UUID id = sub.getUniqueId();
        Ghost ghost = GHOSTS.get(id);
        if (ghost != null && ghost.sub == sub) {
            GHOSTS.remove(id);
            IplStraddleSessionStore.clearGhostView(id);
            IplStraddleRenderCache.invalidateActivePasses();
            return;
        }
        if (ghost != null || IplClientProjections.hasOtherInstance(id, sub)) return;
        forget(id);
    }

    private static void tickGhosts() {
        if (GHOSTS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Ghost> entry : new ArrayList<>(GHOSTS.entrySet())) {
            Ghost ghost = entry.getValue();
            if (ghost.sub.isRemoved()) {
                if (GHOSTS.remove(entry.getKey(), ghost)) IplStraddleSessionStore.clearGhostView(entry.getKey());
                continue;
            }
            ClientSubLevel successor = IplClientProjections.findClientSub(entry.getKey(), ghost.toDim);
            boolean ready = successor != null && successor != ghost.sub && hasGeometry(successor);
            ghost.readyTicks = ready ? ghost.readyTicks + 1 : 0;
            if (ghost.readyTicks >= GHOST_READY_TICKS || now - ghost.startMs > GHOST_TIMEOUT_MS) {
                finalizeGhost(entry.getKey(), ghost);
            }
        }
    }

    /**
     * Преемник показывается, только когда собраны ВСЕ его секции (и ни одна не ждёт
     * пересборки): иначе призрак исчез бы, пока остальные секции ещё строятся, и корабль
     * «догружался» бы кусками на глазах у игрока. Пустые секции Minecraft помечает EMPTY, так
     * что ожидание конечно; если уровень назначения вообще не рисуется, призрак всё равно
     * уходит по {@link #GHOST_TIMEOUT_MS}.
     */
    private static boolean hasGeometry(ClientSubLevel sub) {
        var data = sub.getRenderData();
        if (data == null) return false;
        if (data instanceof VanillaChunkedSubLevelRenderData chunks) {
            boolean anyGeometry = false;
            for (var section : chunks.allRenderSections()) {
                var compiled = section.getCompiled();
                if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED
                    || section.isDirty()) {
                    return false;
                }
                if (anyGeometry) continue;
                for (RenderType type : RenderType.chunkBufferLayers()) {
                    if (!compiled.isEmpty(type)) {
                        anyGeometry = true;
                        break;
                    }
                }
            }
            return anyGeometry;
        }
        return true;
    }

    private static void finalizeGhost(UUID id, Ghost ghost) {
        // A successor tracked before the move never ran continueFromGhost: re-bind here, or the
        // ghost's removal would read as "grabbed body removed" and end the drag.
        ClientSubLevel successor = IplClientProjections.findClientSub(id, ghost.toDim);
        if (successor != null && successor != ghost.sub) {
            IplGrabChainClient.rebindDragToSuccessor(ghost.sub, successor, plotShift(ghost.sub, successor));
        }
        SubLevelContainer container = ghost.sub.isRemoved() ? null : SubLevelContainer.getContainer(ghost.sub.getLevel());
        if (container != null) {
            // Its stop-tracking is still in flight: swallow it when it arrives.
            if (!ghost.stopDeferred) EXPECTED_STOPS.merge(ghost.sub.getLevel().dimension(), 1, Integer::sum);
            container.removeSubLevel(ghost.sub, SubLevelRemovalReason.REMOVED);
        }
        if (GHOSTS.remove(id, ghost)) IplStraddleSessionStore.clearGhostView(id);
        IplStraddleRenderCache.invalidateActivePasses();
    }

    public static final class RemoteCallables {

        /**
         * Same-dimension flip. Reliable, ordered frame boundary captured AFTER the source
         * tick's tracking observer: tick < boundary is the old chart, tick >= boundary the
         * new one. Client interpolation remains delayed; no synthetic pose is inserted.
         */
        public static void handoff(
            String subLevelUuid, String dimensionId, String portalTransform,
            String portalNbtB64, String firstDestinationTick, String coordinateFrame, String inverseGrabLink
        ) {
            UUID id = UUID.fromString(subLevelUuid);
            long destinationFrame = Long.parseLong(coordinateFrame);
            if (destinationFrame <= frame(id)) return;
            if (IplClientProjections.findClientSub(id, dimensionKey(dimensionId)) == null) {
                // Not tracked here: a later full sync is already in the new chart.
                enterFrame(id, destinationFrame);
                return;
            }
            var queue = PENDING_HANDOFFS.computeIfAbsent(id, ignored -> new ArrayDeque<>());
            PendingHandoff next = new PendingHandoff(dimensionId, portalTransform, portalNbtB64,
                Integer.parseInt(firstDestinationTick), destinationFrame, inverseGrabLink);
            queue.addLast(next);
            // Drained at the client tick tail (applyPendingHandoffs).
        }

        /**
         * Full-sync bootstrap (sent before Sable's start-tracking): the body's current frame on
         * the server. A body this client forgot restarted at frame 0; a full sync is always in
         * the current chart, so adopt it -- unless a transit of this body is in flight here,
         * whose own RPCs carry the frames in order.
         */
        public static void trackFrame(String subLevelUuid, String coordinateFrame) {
            UUID id = UUID.fromString(subLevelUuid);
            long serverFrame = Long.parseLong(coordinateFrame);
            if (GHOSTS.containsKey(id) || PENDING_HANDOFFS.containsKey(id)) return;
            // Only a body this client does not show at all: a live instance keeps its frame
            // through its own handoff/move RPCs, which must never be pre-empted.
            if (IplClientProjections.findClientSub(id) != null) return;
            if (serverFrame > frame(id)) {
                LOG.debug("[IPL-PARENT-SYNC] adopt frame {} for {} (was {})", serverFrame, id, frame(id));
                enterFrame(id, serverFrame);
            }
        }

        /**
         * Cross-dimension move, delivered before Sable's stop-tracking of the old object.
         * The old object becomes a ghost; the successor arrives via start-tracking in the
         * destination level (or is already tracked there) and takes over once it has geometry.
         */
        public static void move(
            String subLevelUuid, String fromDimensionId, String toDimensionId, String portalTransform,
            String portalNbtB64, String coordinateFrame, String inverseGrabLink
        ) {
            UUID id = UUID.fromString(subLevelUuid);
            long destinationFrame = Long.parseLong(coordinateFrame);
            if (destinationFrame <= frame(id)) return;
            drainHandoffsNow(id);
            Ghost previous = GHOSTS.get(id);
            if (previous != null) finalizeGhost(id, previous);

            ResourceKey<Level> from = dimensionKey(fromDimensionId);
            ClientSubLevel old = IplClientProjections.findClientSub(id, from);
            if (old != null) {
                PortalMapping mapping = PortalMapping.decode(portalTransform);
                Portal surrogate = IplStraddleSessionStore.resolveHandoffPortal(
                    mapping.portalId(), portalNbtB64, old.getLevel());
                ResourceKey<Level> to = dimensionKey(toDimensionId);
                Ghost ghost = new Ghost(old, to, mapping, surrogate, System.currentTimeMillis(), frame(id));
                GHOSTS.put(id, ghost);
                // Смена кадра ниже заменяет сессии и метки старого кадра; призрак рисует их
                // копию, пока не придёт первый снимок преемника.
                IplStraddleSessionStore.captureGhostView(id);
            }
            enterFrame(id, destinationFrame);
            IplStraddleRenderCache.invalidateActivePasses();
            LOG.debug("[IPL-PARENT-SYNC] move {} {} -> {} (ghost={})", id, fromDimensionId,
                toDimensionId, old != null);
        }

        /** Ordered channel: earlier same-dimension flips must land before a move. */
        private static void drainHandoffsNow(UUID id) {
            ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.remove(id);
            if (pending == null) return;
            for (PendingHandoff handoff : pending) applyHandoffWhenVisuallyClear(id, handoff, true);
        }

        private static boolean applyHandoffWhenVisuallyClear(UUID id, PendingHandoff handoff, boolean force) {
            ClientSubLevel client = IplClientProjections.findClientSub(id, dimensionKey(handoff.dimensionId()));
            if (client == null) {
                enterFrame(id, Math.max(frame(id), handoff.destinationFrame()));
                return true;
            }
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            ClientSubLevelContainer container = ClientSubLevelContainer.getContainer(client.getLevel());
            if (container == null) return false;
            var clock = container.getInterpolation();
            // Server completion is authority. lastInterpolationTick includes the older endpoint.
            boolean reached = clock.lastInterpolationTick >= handoff.firstDestinationTick() - 1;
            // With a configured delay > 1.5, Sable's stopped clock can saturate before
            // the last snapshot. Permit the frame map there; preserve the delayed poses.
            boolean stoppedAtEnd = clock.isStopped()
                && clock.getTickPointer() >= handoff.firstDestinationTick() - 1
                    + 1.5 - clock.getInterpolationDelay() - 1.0e-8;
            if (!force && !reached && !stoppedAtEnd && !mapping.hasFullyClearedSourcePlane(client)) return false;
            if (handoff.destinationFrame() <= frame(id)) return true;
            if (handoff.destinationFrame() != frame(id) + 1L) {
                throw new IllegalStateException("Missing coordinate-frame handoff for " + id);
            }
            applyHandoff(client, mapping);
            enterFrame(id, handoff.destinationFrame());
            IplStraddleSessionStore.releaseHandoffPortal();
            return true;
        }

        private static void applyHandoff(ClientSubLevel clientSubLevel, PortalMapping mapping) {
            UUID subLevelId = clientSubLevel.getUniqueId();
            // Every buffered pose is normalized to the CURRENT visible chart on receipt.
            // Rebase that entire chart once, leaving timestamps, clock and stopped state
            // untouched. Clearing/reseeding at the newest tick freezes a delayed pointer.
            Pose3d mappedLogicalPose = mapping.mapPose(new Pose3d(clientSubLevel.logicalPose()));
            Pose3d mappedLastPose = mapping.mapPose(new Pose3d(clientSubLevel.lastPose()));
            SubLevelSnapshotInterpolator interpolator = clientSubLevel.getInterpolator();
            synchronized (interpolator.buffer) {
                for (int i = 0; i < interpolator.buffer.size(); i++) {
                    var old = interpolator.buffer.get(i);
                    interpolator.buffer.set(i, new SubLevelSnapshotInterpolator.Snapshot(
                        old.gameTick(), mapping.mapPose(new Pose3d(old.pose()))));
                }
                Pose3d running = ((SnapshotInterpolatorAccessor) interpolator).ipl$getRunningSnapshot();
                running.set(mapping.mapPose(new Pose3d(running)));
            }
            ((SubLevelAccessor) clientSubLevel).ipl$getPreviousPose().set(mappedLastPose);
            clientSubLevel.logicalPose().set(mappedLogicalPose);
            clientSubLevel.forceUpdateBounds();
            var bounds = clientSubLevel.getPlot().getBoundingBox();
            if (bounds != null) {
                var swept = (BoundingBox3d) clientSubLevel.boundingBox();
                for (int i = 0; i < 8; i++) {
                    Vec3 corner = mappedLastPose.transformPosition(new Vec3(
                        (i & 1) == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                        (i & 2) == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                        (i & 4) == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
                    swept.minX = Math.min(swept.minX, corner.x);
                    swept.minY = Math.min(swept.minY, corner.y);
                    swept.minZ = Math.min(swept.minZ, corner.z);
                    swept.maxX = Math.max(swept.maxX, corner.x);
                    swept.maxY = Math.max(swept.maxY, corner.y);
                    swept.maxZ = Math.max(swept.maxZ, corner.z);
                }
            }
            IplStraddleRenderCache.invalidateActivePasses();
            // Rebuild Sable's cached render pose from the separately mapped endpoints.
            ((ClientSubLevelAccessor) clientSubLevel).ipl$setLastRenderPosePartialTick(-1.0f);
            LOG.debug("[IPL-PARENT-SYNC] handoff applied for {} pose=({},{},{})", subLevelId,
                String.format("%.1f", mappedLogicalPose.position().x()),
                String.format("%.1f", mappedLogicalPose.position().y()),
                String.format("%.1f", mappedLogicalPose.position().z()));
        }

        /**
         * IP only sends portal entities that are relevant to the client camera. A tracked
         * construction can cross a distant portal in one tick, so carry the portal transform
         * in the handoff itself rather than leaving its destination-frame switch dependent on
         * that entity being in the client's render list.
         */
        public record PortalMapping(
            Vec3 origin,
            Vec3 destination,
            Quaterniond rotation,
            double scale,
            Vec3 sourceNormal,
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
                    new Vec3(decoded[0], decoded[1], decoded[2]),
                    new Vec3(decoded[3], decoded[4], decoded[5]),
                    new Quaterniond(decoded[6], decoded[7], decoded[8], decoded[9]), decoded[10],
                    new Vec3(decoded[11], decoded[12], decoded[13]),
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

            private double minimumSourceDepth(ClientSubLevel sub, Pose3dc pose) {
                var bounds = sub.getPlot().getBoundingBox();
                if (bounds == null) return Double.NEGATIVE_INFINITY;
                Vec3 direction = sourceNormal.scale(-1.0);
                double min = Double.POSITIVE_INFINITY;
                for (int i = 0; i < 8; i++) {
                    Vec3 point = pose.transformPosition(new Vec3(
                        (i & 1) == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                        (i & 2) == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                        (i & 4) == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
                    min = Math.min(min, point.subtract(origin).dot(direction));
                }
                return min;
            }

            boolean hasFullyClearedSourcePlane(ClientSubLevel sub) {
                return minimumSourceDepth(sub, sub.lastPose()) >= -1.0e-8
                    && minimumSourceDepth(sub, sub.logicalPose()) >= -1.0e-8;
            }
        }

        static ResourceKey<Level> dimensionKey(String dimensionId) {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId));
        }
    }
}
