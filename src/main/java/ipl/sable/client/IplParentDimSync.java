package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import ipl.sable.mixin.client.IplClientSubLevelRenderPoseAccessor;
import ipl.sable.mixin.client.IplSnapshotInterpolatorAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
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

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-parent-sync");

    /** RPC delivery can precede the redirected full-sync that creates the client sub-level. */
    private static final Map<UUID, PendingHandoff> PENDING_HANDOFFS = new HashMap<>();

    private IplParentDimSync() {}

        private record PendingHandoff(String parentDimId, String portalTransform) {}

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

    /** Retries handoffs that arrived before their client sub-level was created. */
    public static void applyPendingHandoffs() {
        if (PENDING_HANDOFFS.isEmpty()) return;

        Iterator<Map.Entry<UUID, PendingHandoff>> iterator = PENDING_HANDOFFS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingHandoff> entry = iterator.next();
            PendingHandoff pending = entry.getValue();
            try {
                if (RemoteCallables.applyHandoff(
                    entry.getKey(), pending.parentDimId(), pending.portalTransform()
                )) {
                    iterator.remove();
                }
            } catch (Throwable t) {
                iterator.remove();
                LOG.error("[IPL-PARENT-SYNC] failed deferred handoff for {}", entry.getKey(), t);
            }
        }
    }

    public static final class RemoteCallables {

        public static void setParent(String subLevelUuid, String parentDimId) {
            try {
                SubLevel subLevel = findHostedSubLevel(subLevelUuid, parentDimId);
                if (subLevel == null) return;
                setParent(subLevel, parentDimId);

                LOG.info("[IPL-PARENT-SYNC] sub-level {} parent={} (client)",
                    subLevelUuid, parentDimId);
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to apply parent stamp for {}", subLevelUuid, t);
            }
        }

        /**
         * Atomically switches an already-tracked ship from its source frame to its
         * destination frame. Unlike a normal parent stamp, this also resets Sable's
         * interpolation timeline into destination space. The server pose is deliberately not
         * used as the baseline: it is ahead of Sable's interpolation delay and would produce
         * a visible forward jump on every smooth crossing.
         */
        public static void handoff(String subLevelUuid, String parentDimId, String portalTransform) {
            try {
                UUID subLevelId = UUID.fromString(subLevelUuid);
                if (!applyHandoff(subLevelId, parentDimId, portalTransform)) {
                    PENDING_HANDOFFS.put(subLevelId, new PendingHandoff(parentDimId, portalTransform));
                }
            } catch (Throwable t) {
                LOG.error("[IPL-PARENT-SYNC] failed to hand off hosted sub-level {}", subLevelUuid, t);
            }
        }

        private static boolean applyHandoff(UUID subLevelId, String parentDimId, String portalTransform) {
            SubLevel subLevel = findHostedSubLevel(subLevelId.toString(), parentDimId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)) return false;

            // Projection rendering uses this exact delayed pose and portal transform. The
            // server includes it because a fast crossing can finish before IP tracks the
            // source portal entity for this client.
            Pose3d sourcePose = new Pose3d(clientSubLevel.renderPose());
            PortalMapping mapping = PortalMapping.decode(portalTransform);
            Pose3d pose = mapping.mapPose(sourcePose);

            SubLevelSnapshotInterpolator interpolator = clientSubLevel.getInterpolator();
            // Do not clear the delayed snapshot timeline. The first post-flip movement
            // packet would then replace the visual pose with a newer server pose, causing
            // a second jump. Mapping every buffered snapshot keeps interpolation continuous
            // until destination-space packets naturally extend the same timeline.
            synchronized (interpolator.buffer) {
                for (int i = 0; i < interpolator.buffer.size(); i++) {
                    SubLevelSnapshotInterpolator.Snapshot snapshot = interpolator.buffer.get(i);
                    interpolator.buffer.set(i, new SubLevelSnapshotInterpolator.Snapshot(
                        snapshot.gameTick(), mapping.mapPose(new Pose3d(snapshot.pose()))
                    ));
                }
            }
            ((IplSnapshotInterpolatorAccessor) interpolator).ipl$getRunningSnapshot().set(pose);
            clientSubLevel.logicalPose().set(pose);
            clientSubLevel.updateLastPose();
            clientSubLevel.forceUpdateBounds();

            // Do not expose the new parent until every client pose is in destination
            // space. A pass already in progress may otherwise render a stale source
            // projection as well as the newly native destination sub-level.
            setParent(clientSubLevel, parentDimId);
            // Staff drag frame changes ride the dedicated grab-chain rebase RPC (ordered
            // after this handoff on the same channel); nothing staff-related to do here.
            // Straddle parity is server-synced state now (IplStraddleSessionStore); the
            // "crossed" session-end snapshot precedes this handoff on the ordered channel,
            // so there is no client-side latch left to clear here.
            IplStraddleRenderCache.invalidateActivePasses();
            // Rebuild Sable's cached render pose from the mapped endpoints now. Both
            // endpoints are the same handoff pose, so this produces no interpolated
            // movement while avoiding a one-client-tick pose hold after the teleport.
            ((IplClientSubLevelRenderPoseAccessor) clientSubLevel)
                .ipl$setLastRenderPosePartialTick(-1.0f);
            LOG.info("[IPL-PARENT-SYNC] handoff applied for {} -> parent {} pose=({},{},{})",
                subLevelId, parentDimId,
                String.format("%.1f", pose.position().x()),
                String.format("%.1f", pose.position().y()),
                String.format("%.1f", pose.position().z()));
            return true;
        }

        /**
         * IP only sends portal entities that are relevant to the client camera. A tracked
         * construction can cross a distant portal in one tick, so carry the portal transform
         * in the handoff itself rather than leaving its destination-frame switch dependent on
         * that entity being in the client's render list.
         */
        private record PortalMapping(
            net.minecraft.world.phys.Vec3 origin,
            net.minecraft.world.phys.Vec3 destination,
            Quaterniond rotation,
            double scale,
            net.minecraft.world.phys.Vec3 sourceNormal,
            UUID portalId
        ) {
            static PortalMapping decode(String encoded) {
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

        private static void setParent(SubLevel subLevel, String parentDimId) {
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
