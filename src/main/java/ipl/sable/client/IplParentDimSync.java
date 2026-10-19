package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import ipl.sable.mixin.client.IplClientSubLevelRenderPoseAccessor;
import ipl.sable.mixin.IplSubLevelPreviousPoseAccessor;
import ipl.sable.mixin.client.IplSnapshotInterpolatorAccessor;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
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
 */
public final class IplParentDimSync {

    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ipl.portalDiagnostics");

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    /** RPC delivery can precede the redirected full-sync that creates the client sub-level. */
    private static final Map<UUID, java.util.ArrayDeque<PendingHandoff>> PENDING_HANDOFFS = new HashMap<>();

    /** Parent stamps that arrived before their client sub-level was created (retried per tick). */
    private static final Map<UUID, PendingParentStamp> PENDING_PARENT_STAMPS = new HashMap<>();

    private record PendingParentStamp(String parentDimId, long frame, long queuedAtMs) {}

    private static final Map<UUID, Long> FRAMES = new HashMap<>();
    public static long frame(UUID id) { return FRAMES.getOrDefault(id, 0L); }
    private static void enterFrame(UUID id, long next) {
        long old = frame(id);
        FRAMES.put(id, next);
        if (old != next) IplStraddleSessionStore.enterFrame(id, next);
    }

    private IplParentDimSync() {}

    private record PendingHandoff(
        String parentDimId, String portalTransform, String portalNbtB64,
        int firstDestinationTick, long destinationFrame, String inverseGrabLink, boolean awaitingClientAllocation
    ) {}

    private static long ipl$lastDiagMs = 0;

    /**
     * Round-trip bring-up diagnostic: per hosted client ship, the exact links that can
     * die independently — parent duck, render pose, session store, portal resolution.
     * 5s cadence; remove after the declarative-straddle stack stabilizes.
     */
    public static void clientHeartbeat() {
        if (!DIAGNOSTICS) return;
        long now = System.currentTimeMillis();
        if (now - ipl$lastDiagMs < 5000) return;
        ipl$lastDiagMs = now;

        SubLevelContainer container = IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (!(sub instanceof ClientSubLevel clientSub) || sub.isRemoved()) continue;
            Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(sub);
            var pos = clientSub.renderPose().position();
            LOG.info("[IPL-CLIENT-DIAG] ship={} parent={} pose=({},{},{}) portal={}",
                sub.getUniqueId(),
                parent == null ? "NULL" : parent.dimension().location(),
                String.format("%.1f", pos.x()), String.format("%.1f", pos.y()),
                String.format("%.1f", pos.z()),
                IplStraddleSessionStore.debugPortalKind(clientSub));
        }
    }

    /** Object lifetime, not portal lifetime. Called on removal and disconnect. */
    public static void forget(UUID id) {
        PENDING_HANDOFFS.remove(id);
        PENDING_PARENT_STAMPS.remove(id);
        FRAMES.remove(id);
        IplStraddleSessionStore.clearAllVisualState(id);
    }

    public static void clear() {
        PENDING_HANDOFFS.clear();
        PENDING_PARENT_STAMPS.clear();
        FRAMES.clear();
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
        if (queue == null || queue.isEmpty() || queue.peekFirst().awaitingClientAllocation())
            return authoritative;
        var result = authoritative;
        for (var it = queue.descendingIterator(); it.hasNext();) {
            var handoff = it.next();
            if (handoff.destinationFrame() > chainFrame) continue;
            var inverse = ipl.sable.transit.IplGrabLink.decode(handoff.inverseGrabLink());
            if (inverse != null) result = ipl.sable.transit.IplGrabLink.append(result, inverse);
        }
        return result;
    }

    /** Retries handoffs that arrived before their client sub-level was created. */
    public static void applyPendingHandoffs() {
        if (!PENDING_HANDOFFS.isEmpty()) {
            Iterator<Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>>> iterator =
                PENDING_HANDOFFS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, java.util.ArrayDeque<PendingHandoff>> entry = iterator.next();
                java.util.ArrayDeque<PendingHandoff> pending = entry.getValue();
                try {
                    // A fresh destination full-sync already carries destination-frame poses.
                    // Wait for its parent stamp to consume this queue instead of mapping those
                    // initial poses through the portal a second time.
                    if (pending.peekFirst().awaitingClientAllocation()) continue;
                    // A client can receive several ordered parent flips before the redirected
                    // full-sync creates its hosted sub-level. Apply every transform in order:
                    // keeping only the latest transform maps an original-frame pose through a
                    // later portal and breaks portal chains/self-recursive portals.
                    while (!pending.isEmpty()) {
                        PendingHandoff next = pending.peekFirst();
                        RemoteCallables.beginHandoffVisual(entry.getKey(), next);
                        if (!RemoteCallables.applyHandoffWhenVisuallyClear(
                            entry.getKey(), next
                        )) {
                            break;
                        }
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
        // Parent stamps must run after handoffs. A stamp only changes ownership; applying it
        // first would expose a source-frame delayed pose in its destination world for one tick.
        applyPendingParentStamps();
    }

    /**
     * While visual handoff waits for delayed source geometry to exit, incoming server snapshots
     * are already in the destination frame. Express them back in the current client frame so
     * Sable never interpolates a source pose directly toward a destination coordinate.
     */
    public static Pose3dc mapPendingDestinationSnapshotBack(UUID subLevelId, int gameTick, Pose3dc snapshot) {
        java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
        if (pending == null || pending.isEmpty() || pending.peekFirst().awaitingClientAllocation()) {
            return snapshot;
        }
        Pose3d mapped = new Pose3d(snapshot);
        for (java.util.Iterator<PendingHandoff> it = pending.descendingIterator(); it.hasNext();) {
            PendingHandoff handoff = it.next();
            // The packet preceding the RPC is still SOURCE-frame. Only packets at or
            // after the captured tracking watermark belong to this destination chart.
            if (gameTick >= handoff.firstDestinationTick()) {
                mapped = RemoteCallables.PortalMapping.decode(handoff.portalTransform())
                    .mapPoseInverse(mapped);
            }
        }
        return mapped;
    }

    /** Retries parent stamps that raced their StartTracking allocation. */
    private static void applyPendingParentStamps() {
        if (PENDING_PARENT_STAMPS.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingParentStamp>> iterator = PENDING_PARENT_STAMPS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingParentStamp> entry = iterator.next();
            try {
                if (PENDING_HANDOFFS.containsKey(entry.getKey())) continue;
                if (now - entry.getValue().queuedAtMs() > 30_000) {
                    IplStraddleSessionStore.clearAllVisualState(entry.getKey());
                    iterator.remove(); // ship never materialized client-side — stop retrying
                    continue;
                }
                SubLevel subLevel = RemoteCallables.findHostedSubLevel(
                    entry.getKey().toString(), entry.getValue().parentDimId());
                if (subLevel != null) {
                    if (entry.getValue().frame() >= frame(entry.getKey())) {
                        RemoteCallables.setParentInternal(subLevel, entry.getValue().parentDimId());
                        enterFrame(entry.getKey(), entry.getValue().frame());
                    }
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

        public static void setParent(String subLevelUuid, String parentDimId, String coordinateFrame) {
            try {
                UUID subLevelId = UUID.fromString(subLevelUuid);
                long stampFrame = Long.parseLong(coordinateFrame);
                if (stampFrame < frame(subLevelId)) return;
                SubLevel subLevel = findHostedSubLevel(subLevelUuid, parentDimId);
                if (subLevel == null) {
                    // The RPC raced the StartTracking allocation. Without a retry the ship
                    // keeps a null client parent and the hosted render gather filters it out
                    // FOREVER — "the ship exists (physics, collision) but is invisible".
                    // Single-block ships (swivel tops, shattered blocks, rope connectors)
                    // never emit further updates that could heal it, so they stayed gone.
                    PENDING_PARENT_STAMPS.put(subLevelId,
                        new PendingParentStamp(parentDimId, stampFrame, System.currentTimeMillis()));
                    return;
                }
                if (discardPreAllocationHandoffs(subLevelId, parentDimId, stampFrame)) {
                    // This client did not have a source-frame body when the handoff arrived.
                    // Its just-created full sync is already in the final destination frame.
                    setParentInternal(subLevel, parentDimId);
                    enterFrame(subLevelId, stampFrame);
                    IplStraddleSessionStore.clearHandoffVisual(subLevelId);
                    PENDING_PARENT_STAMPS.remove(subLevelId);
                    return;
                }
                if (PENDING_HANDOFFS.containsKey(subLevelId)) {
                    // A parent stamp can race the full-sync that a queued handoff waits for.
                    // Keep it behind the FIFO so ownership never becomes visible before its
                    // delayed render timeline has been mapped into that frame.
                    PENDING_PARENT_STAMPS.put(subLevelId,
                        new PendingParentStamp(parentDimId, stampFrame, System.currentTimeMillis()));
                    return;
                }
                setParentInternal(subLevel, parentDimId);
                enterFrame(subLevelId, stampFrame);
                PENDING_PARENT_STAMPS.remove(subLevelId);

                LOG.debug("[IPL-PARENT-SYNC] sub-level {} parent={} (client)",
                    subLevelUuid, parentDimId);
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to apply parent stamp for {}", subLevelUuid, t);
            }
        }

        /**
         * Reliable, ordered frame boundary, captured AFTER the source tick's tracking
         * observer. Tick < boundary is source; tick >= boundary is destination.
         * Client interpolation remains delayed; no synthetic tick or pose is inserted.
         */
        public static void handoff(
            String subLevelUuid, String parentDimId, String portalTransform,
            String portalNbtB64, String firstDestinationTick, String coordinateFrame, String inverseGrabLink
        ) {
            try {
                UUID id = UUID.fromString(subLevelUuid);
                long destinationFrame = Long.parseLong(coordinateFrame);
                if (destinationFrame <= frame(id)) return;
                boolean awaiting = !(findHostedSubLevel(subLevelUuid, parentDimId)
                    instanceof ClientSubLevel);
                var queue = PENDING_HANDOFFS.computeIfAbsent(id,
                    ignored -> new java.util.ArrayDeque<>());
                PendingHandoff next = new PendingHandoff(parentDimId, portalTransform,
                    portalNbtB64, Integer.parseInt(firstDestinationTick), destinationFrame, inverseGrabLink, awaiting);
                queue.addLast(next);
                if (queue.size() == 1 && !awaiting) beginHandoffVisual(id, next);
                // Drain only at the client tick tail, after all source/destination packets
                // in this network batch have been normalized and interpolation has ticked.
            } catch (RuntimeException t) {
                LOG.error("[IPL-PARENT-SYNC] invalid handoff for {}", subLevelUuid, t);
            }
        }

        private static boolean applyHandoffWhenVisuallyClear(UUID id, PendingHandoff handoff) {
            SubLevel sub = findHostedSubLevel(id.toString(), handoff.parentDimId());
            if (!(sub instanceof ClientSubLevel client)) return false;
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            ClientSubLevelContainer container = ClientSubLevelContainer.getContainer(client.getLevel());
            if (container == null) return false;
            var clock = container.getInterpolation();
            // Server completion is authority. A missing render sample/portal entity must
            // NEVER veto it. lastInterpolationTick includes the older render endpoint.
            boolean reached = clock.lastInterpolationTick >= handoff.firstDestinationTick() - 1;
            // With a configured delay > 1.5, Sable's stopped clock can saturate before
            // the last snapshot. Permit the frame map there; preserve the delayed poses.
            boolean stoppedAtEnd = clock.isStopped()
                && clock.getTickPointer() >= handoff.firstDestinationTick() - 1
                    + 1.5 - clock.getInterpolationDelay() - 1.0e-8;
            if (!reached && !stoppedAtEnd && !mapping.hasFullyClearedSourcePlane(client)) return false;
            if (handoff.destinationFrame() <= frame(id)) return true;
            if (handoff.destinationFrame() != frame(id) + 1L) {
                throw new IllegalStateException("Missing coordinate-frame handoff for " + id);
            }
            applyHandoff(id, handoff.parentDimId(), mapping);
            enterFrame(id, handoff.destinationFrame());
            IplStraddleSessionStore.clearHandoffVisual(id);
            IplStraddleSessionStore.releaseHandoffPortal();
            return true;
        }

        /** Only the FIFO head receives a render tail; later recursive handoffs wait. */
        private static void beginHandoffVisual(UUID subLevelId, PendingHandoff handoff) {
            PortalMapping mapping = PortalMapping.decode(handoff.portalTransform());
            IplStraddleSessionStore.beginHandoffVisual(
                subLevelId, mapping.portalId(), handoff.portalNbtB64());
        }

        private static boolean discardPreAllocationHandoffs(UUID subLevelId, String parentDimId, long stampFrame) {
            java.util.ArrayDeque<PendingHandoff> pending = PENDING_HANDOFFS.get(subLevelId);
            if (pending == null || pending.isEmpty() || !pending.peekFirst().awaitingClientAllocation()) {
                return false;
            }
            PendingHandoff last = pending.peekLast();
            if (!last.parentDimId().equals(parentDimId) || stampFrame < last.destinationFrame()) return false;
            PENDING_HANDOFFS.remove(subLevelId);
            return true;
        }

        private static void applyHandoff(
            UUID subLevelId, String parentDimId, PortalMapping mapping
        ) {
            SubLevel subLevel = findHostedSubLevel(subLevelId.toString(), parentDimId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) return;
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

            // Do not expose the new parent until every client pose is in destination
            // space. A pass already in progress may otherwise render a stale source
            // projection as well as the newly native destination sub-level.
            setParentInternal(clientSubLevel, parentDimId);
            // The retired split only bridges server session-end to this exact mapped pose.
            // Once the parent frame becomes visible, retaining its old portal would clip the
            // native destination draw with a source-frame plane.
            IplStraddleSessionStore.clearRetiredForHandoff(subLevelId);
            // Staff's motor/orientation follow server authority on its own RPC. Do not
            // apply those transforms again when this delayed visual chart catches up.
            // Straddle parity is server-synced state now (IplStraddleSessionStore); the
            // "crossed" session-end snapshot precedes this handoff on the ordered channel,
            // so there is no client-side latch left to clear here.
            IplStraddleRenderCache.invalidateActivePasses();
            // Rebuild Sable's cached render pose from the separately mapped endpoints.
            // Portal isometry commutes with the interpolation, so exit motion continues
            // exactly where its destination projection left it.
            ((IplClientSubLevelRenderPoseAccessor) clientSubLevel)
                .ipl$setLastRenderPosePartialTick(-1.0f);
            LOG.debug("[IPL-PARENT-SYNC] handoff applied for {} -> parent {} pose=({},{},{})",
                subLevelId, parentDimId,
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
