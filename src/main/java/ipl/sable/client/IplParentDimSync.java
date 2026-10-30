package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.mixin.IplSubLevelPreviousPoseAccessor;
import ipl.sable.mixin.client.IplClientSubLevelRenderPoseAccessor;
import ipl.sable.mixin.client.IplSnapshotInterpolatorAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Iterator;
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
 *       the switch happens in one frame with neither a gap nor a double image.</li>
 * </ul>
 */
public final class IplParentDimSync {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    private static final long GHOST_TIMEOUT_MS = 3000L;
    private static final int GHOST_READY_TICKS = 3;
    /**
     * Максимум тиков, на которые призрак продолжает движение сам (≈0.5 с), пока преемник
     * межизмеренческого перехода ещё не пришёл.
     */
    private static final int GHOST_EXTRAPOLATION_TICKS = 10;

    /** Same-dimension flips waiting for the delayed render stream, FIFO per body. */
    private static final Map<UUID, java.util.ArrayDeque<PendingHandoff>> PENDING_HANDOFFS = new HashMap<>();

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
        /** Метка часов назначения минус метка часов источника для одного серверного тика. */
        final int clockOffset;
        int readyTicks;
        boolean stopDeferred;
        /** Преемник уже получил историю поз призрака и выровненные часы. */
        boolean seeded;
        /** Сдвиг plot-локальных координат преемника относительно призрака (если слот плота другой). */
        double plotShiftX;
        double plotShiftZ;
        /** Метка последнего НАСТОЯЩЕГО снимка призрака (дальше — экстраполяция), или MIN_VALUE. */
        int lastRealTick = Integer.MIN_VALUE;
        /** Последний настоящий снимок и скорость за тик (линейная + поворот), для экстраполяции. */
        @Nullable Pose3d extrapolationBase;
        final Vector3d linearPerTick = new Vector3d();
        final Quaterniond rotationPerTick = new Quaterniond();

        Ghost(ClientSubLevel sub, ResourceKey<Level> toDim, RemoteCallables.PortalMapping mapping,
              @Nullable Portal surrogate, long startMs, int clockOffset) {
            this.sub = sub;
            this.toDim = toDim;
            this.mapping = mapping;
            this.surrogate = surrogate;
            this.startMs = startMs;
            this.clockOffset = clockOffset;
        }
    }

    private IplParentDimSync() {}

    public static long frame(UUID id) { return FRAMES.getOrDefault(id, 0L); }

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
    public static java.util.List<ipl.sable.transit.IplGrabLink> visibleGrabChain(
        UUID id, long chainFrame, java.util.List<ipl.sable.transit.IplGrabLink> authoritative
    ) {
        var queue = PENDING_HANDOFFS.get(id);
        if (queue == null || queue.isEmpty()) return authoritative;
        var result = authoritative;
        for (var it = queue.descendingIterator(); it.hasNext();) {
            var handoff = it.next();
            if (handoff.destinationFrame() > chainFrame) continue;
            var inverse = ipl.sable.transit.IplGrabLink.decode(handoff.inverseGrabLink());
            if (inverse != null) result = ipl.sable.transit.IplGrabLink.append(result, inverse);
        }
        return result;
    }

    /** Client tick tail: drains same-dimension handoffs, then advances transit ghosts. */
    public static void applyPendingHandoffs() {
        if (!PENDING_HANDOFFS.isEmpty()) {
            Iterator<Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>>> iterator =
                PENDING_HANDOFFS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>> entry = iterator.next();
                java.util.ArrayDeque<PendingHandoff> pending = entry.getValue();
                try {
                    // Several ordered flips can queue before the delayed stream catches up;
                    // apply every transform in order (portal chains, self-recursive portals).
                    while (!pending.isEmpty()) {
                        PendingHandoff next = pending.peekFirst();
                        RemoteCallables.beginHandoffVisual(entry.getKey(), next);
                        if (!RemoteCallables.applyHandoffWhenVisuallyClear(entry.getKey(), next, false)) break;
                        pending.removeFirst();
                    }
                    if (pending.isEmpty()) iterator.remove();
                } catch (Throwable t) {
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove();
                    LOG.error("[IPL-PARENT-SYNC] failed deferred handoff for {}", entry.getKey(), t);
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
        java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
        if (pending == null || pending.isEmpty()) return snapshot;
        Pose3d mapped = new Pose3d(snapshot);
        for (java.util.Iterator<PendingHandoff> it = pending.descendingIterator(); it.hasNext();) {
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
        if (GHOSTS.isEmpty() || sub == null) return false;
        Ghost ghost = GHOSTS.get(sub.getUniqueId());
        return ghost != null && ghost.sub == sub;
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
        if (GHOSTS.isEmpty() || sub == null) return null;
        Ghost ghost = GHOSTS.get(sub.getUniqueId());
        if (ghost == null || ghost.sub != sub) return null;
        if (sub.getLevel() instanceof ClientLevel level) {
            Portal live = IplStraddleSessionStore.findLivePortal(level, ghost.mapping.portalId());
            if (live != null) return live;
        }
        return ghost.surrogate;
    }

    /** A ghost follows its successor's live pose, mapped back through the crossing portal. */
    @Nullable
    public static Pose3dc ghostRenderPose(ClientSubLevel sub) {
        if (GHOSTS.isEmpty() || sub == null) return null;
        Ghost ghost = GHOSTS.get(sub.getUniqueId());
        if (ghost == null || ghost.sub != sub) return null;
        ClientSubLevel successor = IplClientProjections.findClientSub(sub.getUniqueId(), ghost.toDim);
        if (successor == null || successor == sub) return null;
        Pose3d back = ghost.mapping.mapPoseInverse(new Pose3d(successor.renderPose()));
        back.rotationPoint().sub(ghost.plotShiftX, 0.0, ghost.plotShiftZ);
        return back;
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
     * Конец обработки start-tracking: преемник межизмеренческого перехода создан Sable.
     *
     * <p>Почему здесь был «подвис как пинг»: у каждого уровня свои часы интерполяции, а
     * start-tracking даёт новому объекту всего два снимка (и первый из них — копия второго,
     * потому что сервер аллоцировал объект с lastPose == pose). Часы контейнера назначения при
     * этом часто стоят или устарели: если игрок давно не получал оттуда движений, Sable
     * зажимает указатель у нижней границы и показывает первый снимок 2–3 тика, а потом
     * навсегда остаётся с удвоенной задержкой. Same-dim переход этого не имеет, потому что там
     * буфер и часы остаются теми же (handoff лишь пересчитывает позы через портал).
     *
     * <p>Решение: переносим историю призрака (буфер снимков источника) через портал в буфер
     * преемника с переводом меток в часы назначения ({@code clockOffset} от сервера) и, если
     * часы назначения не идут синхронно, выставляем их по часам источника. Тогда преемник в
     * первый же кадр показывает ту же позу, что показывал бы призрак, и дальше едет без паузы.
     */
    public static void onSuccessorTracked(Level level, UUID id) {
        Ghost ghost = GHOSTS.get(id);
        if (ghost == null || ghost.seeded || ghost.sub.getLevel() == level) return;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !(container.getSubLevel(id) instanceof ClientSubLevel successor)
            || successor == ghost.sub) return;
        ghost.seeded = true;
        try {
            seedSuccessor(ghost, successor);
        } catch (Throwable t) {
            LOG.error("[IPL-PARENT-SYNC] successor seeding failed for {}", id, t);
        }
    }

    private static void seedSuccessor(Ghost ghost, ClientSubLevel successor) {
        ClientSubLevelContainer from = ClientSubLevelContainer.getContainer(ghost.sub.getLevel());
        ClientSubLevelContainer to = ClientSubLevelContainer.getContainer(successor.getLevel());
        if (from == null || to == null) return;
        var srcClock = from.getInterpolation();
        var dstClock = to.getInterpolation();
        var src = (ipl.sable.mixin.client.IplInterpolationClockAccessor) srcClock;
        var dst = (ipl.sable.mixin.client.IplInterpolationClockAccessor) dstClock;
        int offset = ghost.clockOffset;

        net.minecraft.world.level.ChunkPos a = ghost.sub.getPlot().getChunkMin();
        net.minecraft.world.level.ChunkPos b = successor.getPlot().getChunkMin();
        ghost.plotShiftX = (b.x - a.x) * 16.0;
        ghost.plotShiftZ = (b.z - a.z) * 16.0;

        // 1. История: снимки призрака (карта источника) -> карта назначения, метки -> часы назначения.
        java.util.List<SubLevelSnapshotInterpolator.Snapshot> mapped = new java.util.ArrayList<>();
        SubLevelSnapshotInterpolator ghostInterpolator = ghost.sub.getInterpolator();
        synchronized (ghostInterpolator.buffer) {
            for (SubLevelSnapshotInterpolator.Snapshot snapshot : ghostInterpolator.buffer) {
                // Синтетические (экстраполированные) снимки призрака преемнику не нужны.
                if (ghost.lastRealTick != Integer.MIN_VALUE && snapshot.gameTick() > ghost.lastRealTick) continue;
                Pose3d pose = ghost.mapping.mapPose(new Pose3d(snapshot.pose()));
                pose.rotationPoint().add(ghost.plotShiftX, 0.0, ghost.plotShiftZ);
                mapped.add(new SubLevelSnapshotInterpolator.Snapshot(snapshot.gameTick() + offset, pose));
            }
        }
        if (!mapped.isEmpty()) {
            int newest = mapped.get(mapped.size() - 1).gameTick();
            SubLevelSnapshotInterpolator interpolator = successor.getInterpolator();
            synchronized (interpolator.buffer) {
                // Снимки start-tracking не новее истории призрака — синтетические (lastPose == pose).
                interpolator.buffer.removeIf(snapshot -> snapshot.gameTick() <= newest);
                interpolator.buffer.addAll(0, mapped);
            }
        }

        // 2. Часы: если часы назначения стоят, не получали обновлений или разошлись с часами
        //    источника больше чем на тик — ставим их по часам источника (+ сдвиг меток).
        boolean srcLive = src.ipl$getReceivedFirstUpdate() && !srcClock.isStopped();
        if (srcLive) {
            double expected = src.ipl$getInterpolationTick() + offset;
            double expectedNewest = src.ipl$getMostRecentTick() + offset;
            boolean dstLive = dst.ipl$getReceivedFirstUpdate() && !dstClock.isStopped()
                && dst.ipl$getMostRecentTick() >= expectedNewest - 2.0;
            if (!dstLive || Math.abs(dst.ipl$getInterpolationTick() - expected) > 1.0) {
                dst.ipl$setInterpolationTick(expected);
                dst.ipl$setMostRecentTick(Math.max(dst.ipl$getMostRecentTick(), expectedNewest));
                dst.ipl$setEstimatedServerTickSpeed(src.ipl$getEstimatedServerTickSpeed());
                dst.ipl$setServerMsFromLastUpdate(src.ipl$getServerMsFromLastUpdate());
                dst.ipl$setReceivedFirstUpdate(true);
                dst.ipl$setStopped(false);
                dstClock.mostRecentInterpolationTick = srcClock.mostRecentInterpolationTick + offset;
                dstClock.lastInterpolationTick = srcClock.lastInterpolationTick + offset;
                LOG.debug("[IPL-PARENT-SYNC] aligned {} clock to {} (+{}) for {}",
                    successor.getLevel().dimension().location(), ghost.sub.getLevel().dimension().location(),
                    offset, successor.getUniqueId());
            }
        }

        // 3. Начальные позы — из засеянного буфера по выровненным часам.
        successor.setInitialPosesFrom(dstClock);
        SubLevelSnapshotInterpolator interpolator = successor.getInterpolator();
        synchronized (interpolator.buffer) {
            if (!interpolator.buffer.isEmpty()) {
                interpolator.getSampleAt(dstClock.getTickPointer(),
                    ((IplSnapshotInterpolatorAccessor) interpolator).ipl$getRunningSnapshot());
            }
        }
        successor.forceUpdateBounds();
        ((IplClientSubLevelRenderPoseAccessor) successor).ipl$setLastRenderPosePartialTick(-1.0f);
    }

    /** {@code ClientSubLevel.onRemove}: forget per-body state only when no instance survives. */
    public static void onClientSubRemoved(ClientSubLevel sub) {
        UUID id = sub.getUniqueId();
        Ghost ghost = GHOSTS.get(id);
        if (ghost != null && ghost.sub == sub) {
            GHOSTS.remove(id);
            IplStraddleRenderCache.invalidateActivePasses();
            return;
        }
        if (ghost != null || IplClientProjections.hasOtherInstance(id, sub)) return;
        forget(id);
    }

    private static void tickGhosts() {
        if (GHOSTS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Ghost> entry : new java.util.ArrayList<>(GHOSTS.entrySet())) {
            Ghost ghost = entry.getValue();
            if (ghost.sub.isRemoved()) {
                GHOSTS.remove(entry.getKey(), ghost);
                continue;
            }
            if (!ghost.seeded) {
                try {
                    extendGhostMotion(ghost);
                } catch (RuntimeException t) {
                    LOG.debug("[IPL-PARENT-SYNC] ghost extrapolation failed for {}", entry.getKey(), t);
                }
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
     * Подвисание на выходе в другое измерение (клиентская часть).
     *
     * <p>Русский комментарий (почему): после межизмеренческого переноса старый клиентский
     * объект (призрак) больше не получает снимков — сервер уже ведёт новый объект в другом
     * уровне. Интерполятор Sable досчитывает вперёд максимум ОДИН тик, а затем стоит на
     * последнем снимке. Преемник приходит через start-tracking уровня назначения: из
     * Верхнего мира в Незер — в том же серверном тике, обратно — тиком позже, плюс сеть и
     * сборка чанков плота. Всё это время призрак "висел", а потом прыгал к преемнику.
     * Теперь, пока преемник не засеян, призрак продолжает движение с последней скоростью
     * (линейной и угловой, по двум последним настоящим снимкам) синтетическими снимками
     * на шкале часов источника — не дальше {@link #GHOST_EXTRAPOLATION_TICKS} тиков.
     * Синтетические снимки помечены меткой {@code lastRealTick} и НЕ переносятся в буфер
     * преемника (у него есть настоящие данные).
     */
    private static void extendGhostMotion(Ghost ghost) {
        ClientSubLevelContainer container = ClientSubLevelContainer.getContainer(ghost.sub.getLevel());
        if (container == null) return;
        double pointer = container.getInterpolation().getTickPointer();
        SubLevelSnapshotInterpolator interpolator = ghost.sub.getInterpolator();
        synchronized (interpolator.buffer) {
            int size = interpolator.buffer.size();
            if (size == 0) return;
            if (ghost.extrapolationBase == null) {
                if (size < 2) return;
                SubLevelSnapshotInterpolator.Snapshot a = interpolator.buffer.get(size - 2);
                SubLevelSnapshotInterpolator.Snapshot b = interpolator.buffer.get(size - 1);
                int span = b.gameTick() - a.gameTick();
                if (span <= 0) return;
                ghost.lastRealTick = b.gameTick();
                ghost.extrapolationBase = new Pose3d(b.pose());
                Vector3d pa = new Vector3d(a.pose().position());
                ghost.linearPerTick.set(b.pose().position()).sub(pa).div(span);
                // Поворот за тик: (b * a^-1)^(1/span) в мировой системе.
                Quaterniond delta = new Quaterniond(b.pose().orientation())
                    .mul(new Quaterniond(a.pose().orientation()).conjugate()).normalize();
                if (delta.w < 0.0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
                ghost.rotationPerTick.identity().slerp(delta, 1.0 / span).normalize();
            }
            int newest = interpolator.buffer.get(size - 1).gameTick();
            // Держим один снимок впереди указателя: Sable интерполирует, а не экстраполирует.
            while (newest < pointer + 2.0 && newest - ghost.lastRealTick < GHOST_EXTRAPOLATION_TICKS) {
                newest++;
                int k = newest - ghost.lastRealTick;
                Pose3d next = new Pose3d(ghost.extrapolationBase);
                next.position().fma(k, ghost.linearPerTick);
                Quaterniond step = new Quaterniond();
                for (int i = 0; i < k; i++) step.mul(ghost.rotationPerTick);
                step.mul(ghost.extrapolationBase.orientation(), next.orientation()).normalize();
                interpolator.buffer.add(new SubLevelSnapshotInterpolator.Snapshot(newest, next));
            }
        }
    }

    private static boolean hasGeometry(ClientSubLevel sub) {
        var data = sub.getRenderData();
        if (data == null) return false;
        if (data instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData chunks) {
            for (var section : chunks.allRenderSections()) {
                for (net.minecraft.client.renderer.RenderType type : net.minecraft.client.renderer.RenderType.chunkBufferLayers()) {
                    if (!section.getCompiled().isEmpty(type)) return true;
                }
            }
            return false;
        }
        return true;
    }

    private static void finalizeGhost(UUID id, Ghost ghost) {
        try {
            if (!ghost.sub.isRemoved()) {
                SubLevelContainer container = SubLevelContainer.getContainer(ghost.sub.getLevel());
                if (container != null) {
                    // Its stop-tracking is still in flight: swallow it when it arrives.
                    if (!ghost.stopDeferred) EXPECTED_STOPS.merge(ghost.sub.getLevel().dimension(), 1, Integer::sum);
                    container.removeSubLevel(ghost.sub, SubLevelRemovalReason.REMOVED);
                }
            }
        } catch (Throwable t) {
            LOG.error("[IPL-PARENT-SYNC] ghost removal failed for {}", id, t);
        } finally {
            GHOSTS.remove(id, ghost);
            IplStraddleRenderCache.invalidateActivePasses();
        }
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
            try {
                UUID id = UUID.fromString(subLevelUuid);
                long destinationFrame = Long.parseLong(coordinateFrame);
                if (destinationFrame <= frame(id)) return;
                if (IplClientProjections.findClientSub(id, dimensionKey(dimensionId)) == null) {
                    // Not tracked here: a later full sync is already in the new chart.
                    enterFrame(id, destinationFrame);
                    return;
                }
                var queue = PENDING_HANDOFFS.computeIfAbsent(id, ignored -> new java.util.ArrayDeque<>());
                PendingHandoff next = new PendingHandoff(dimensionId, portalTransform, portalNbtB64,
                    Integer.parseInt(firstDestinationTick), destinationFrame, inverseGrabLink);
                queue.addLast(next);
                if (queue.size() == 1) beginHandoffVisual(id, next);
                // Drained at the client tick tail (applyPendingHandoffs).
            } catch (RuntimeException t) {
                LOG.error("[IPL-PARENT-SYNC] invalid handoff for {}", subLevelUuid, t);
            }
        }

        /**
         * Cross-dimension move, delivered before Sable's stop-tracking of the old object.
         * The old object becomes a ghost; the successor arrives via start-tracking in the
         * destination level and takes over once it has geometry.
         */
        public static void move(
            String subLevelUuid, String fromDimensionId, String toDimensionId, String portalTransform,
            String portalNbtB64, String coordinateFrame, String inverseGrabLink, String clockOffset
        ) {
            try {
                UUID id = UUID.fromString(subLevelUuid);
                long destinationFrame = Long.parseLong(coordinateFrame);
                if (destinationFrame <= frame(id)) return;
                drainHandoffsNow(id);
                Ghost previous = GHOSTS.get(id);
                if (previous != null) finalizeGhost(id, previous);

                ResourceKey<Level> from = dimensionKey(fromDimensionId);
                ClientSubLevel old = IplClientProjections.findClientSub(id, from);
                if (old != null && old.getLevel() instanceof ClientLevel fromLevel) {
                    PortalMapping mapping = PortalMapping.decode(portalTransform);
                    Portal surrogate = IplStraddleSessionStore.resolveHandoffPortal(
                        mapping.portalId(), portalNbtB64, fromLevel);
                    IplClientVisualTransitLatch.clear(id);
                    GHOSTS.put(id, new Ghost(old, dimensionKey(toDimensionId), mapping, surrogate,
                        System.currentTimeMillis(), Integer.parseInt(clockOffset)));
                }
                enterFrame(id, destinationFrame);
                IplStraddleRenderCache.invalidateActivePasses();
                LOG.debug("[IPL-PARENT-SYNC] move {} {} -> {} (ghost={})", id, fromDimensionId,
                    toDimensionId, old != null);
            } catch (RuntimeException t) {
                LOG.error("[IPL-PARENT-SYNC] invalid move for {}", subLevelUuid, t);
            }
        }

        /** Ordered channel: earlier same-dimension flips must land before a move. */
        private static void drainHandoffsNow(UUID id) {
            java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.remove(id);
            if (pending == null) return;
            for (PendingHandoff handoff : pending) {
                try {
                    applyHandoffWhenVisuallyClear(id, handoff, true);
                } catch (Throwable t) {
                    LOG.error("[IPL-PARENT-SYNC] forced handoff failed for {}", id, t);
                }
            }
        }

        private static boolean applyHandoffWhenVisuallyClear(UUID id, PendingHandoff handoff, boolean force) {
            ClientSubLevel client = IplClientProjections.findClientSub(id, dimensionKey(handoff.dimensionId()));
            if (client == null) {
                enterFrame(id, Math.max(frame(id), handoff.destinationFrame()));
                IplStraddleSessionStore.clearHandoffVisual(id);
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
            IplStraddleSessionStore.clearHandoffVisual(id);
            IplStraddleSessionStore.releaseHandoffPortal();
            return true;
        }

        /** Only the FIFO head receives a render tail; later recursive handoffs wait. */
        private static void beginHandoffVisual(UUID subLevelId, PendingHandoff handoff) {
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            IplStraddleSessionStore.beginHandoffVisual(subLevelId, mapping.portalId(), handoff.portalNbtB64());
        }

        private static void applyHandoff(ClientSubLevel clientSubLevel, PortalMapping mapping) {
            UUID subLevelId = clientSubLevel.getUniqueId();
            // Every buffered pose is normalized to the CURRENT visible chart on receipt.
            // Rebase that entire chart once, leaving timestamps, clock and stopped state
            // untouched. Clearing/reseeding at the newest tick freezes a delayed pointer.
            IplClientVisualTransitLatch.clear(subLevelId);
            Pose3d mappedLogicalPose = mapping.mapPose(new Pose3d(clientSubLevel.logicalPose()));
            Pose3d mappedLastPose = mapping.mapPose(new Pose3d(clientSubLevel.lastPose()));
            SubLevelSnapshotInterpolator interpolator = clientSubLevel.getInterpolator();
            synchronized (interpolator.buffer) {
                for (int i = 0; i < interpolator.buffer.size(); i++) {
                    var old = interpolator.buffer.get(i);
                    interpolator.buffer.set(i, new SubLevelSnapshotInterpolator.Snapshot(
                        old.gameTick(), mapping.mapPose(new Pose3d(old.pose()))));
                }
                Pose3d running = ((IplSnapshotInterpolatorAccessor) interpolator).ipl$getRunningSnapshot();
                running.set(mapping.mapPose(new Pose3d(running)));
            }
            ((IplSubLevelPreviousPoseAccessor) clientSubLevel).ipl$getPreviousPose().set(mappedLastPose);
            clientSubLevel.logicalPose().set(mappedLogicalPose);
            clientSubLevel.forceUpdateBounds();
            var bounds = clientSubLevel.getPlot().getBoundingBox();
            if (bounds != null) {
                var swept = (dev.ryanhcode.sable.companion.math.BoundingBox3d) clientSubLevel.boundingBox();
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
            // The retired split only bridges server session-end to this exact mapped pose.
            IplStraddleSessionStore.clearRetiredForHandoff(subLevelId);
            IplStraddleRenderCache.invalidateActivePasses();
            // Rebuild Sable's cached render pose from the separately mapped endpoints.
            ((IplClientSubLevelRenderPoseAccessor) clientSubLevel).ipl$setLastRenderPosePartialTick(-1.0f);
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
