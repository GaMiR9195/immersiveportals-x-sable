package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.UUID;

/**
 * Spawn / sync / despawn primitives for kinematic mirror sub-levels.
 *
 * <p><b>Kinematic mirror design:</b> we let Sable enroll the mirror in the physics
 * pipeline normally (so {@code buildMassTracker} fires, downstream cascades work).
 * Each tick we call {@code pipeline.teleport(mirror, mappedPose)} to pin the
 * pipeline body's pose to whatever the source-portal-mapping produces. The pipeline
 * still simulates the mirror's body each substep (wasted CPU), but its computed
 * pose gets clobbered by our teleport call AND its readback to {@code logicalPose}
 * is cancelled by {@code SableMirrorPhysicsOptOutMixin}. Net result: the mirror's
 * logicalPose tracks the source via portal mapping, the pipeline body matches, no
 * cascade breakage anywhere.
 *
 * <p>The mirror still gets a {@code @Unique} kinematic flag (via
 * {@link IplKinematicSubLevelHolder}). That's read by the {@code updatePose} cancel
 * mixin to identify which sub-levels to skip pose readback for. It's also useful
 * for our own controller logic to filter mirrors out of transit iteration.
 *
 * <p>Mirror UUID is fresh (not source's). Source and mirror coexist in different
 * containers during the approach phase. Per Phase 0 decision -- keeps Sable's
 * per-container UUID maps clean.
 */
public final class MirrorOps {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-mirror");

    private MirrorOps() {}

    /**
     * Spawn a kinematic mirror in the dest dim. Returns the mirror's sub-level if
     * created, or null on any failure (logged at WARN).
     */
    public static ServerSubLevel spawnMirror(ServerSubLevel source, Portal portal) {
        MinecraftServer server = source.getLevel().getServer();
        if (server == null) {
            LOG.warn("[IPL-MIRROR] aborted spawn: source has no server (uuid={})", source.getUniqueId());
            return null;
        }

        ServerLevel destLevel = server.getLevel(portal.getDestDim());
        if (destLevel == null) {
            LOG.warn("[IPL-MIRROR] aborted spawn: dest dim {} not loaded", portal.getDestDim().location());
            return null;
        }
        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) {
            LOG.warn("[IPL-MIRROR] aborted spawn: dest dim {} has no container",
                destLevel.dimension().location());
            return null;
        }

        int[] plotXZ = SableTransitOps.findFirstEmptyPlotForMirror(destContainer);
        if (plotXZ == null) {
            LOG.warn("[IPL-MIRROR] aborted spawn: dest dim {} plot grid full",
                destLevel.dimension().location());
            return null;
        }

        // Compute mirror's initial pose via portal transform.
        Pose3d mirrorPose = SableTransitOps.computeMappedPose(source.logicalPose(), portal);

        UUID mirrorUuid = UUID.randomUUID();
        UUID sourceUuid = source.getUniqueId();
        UUID portalUuid = portal.getUUID();

        // Allocate the mirror with normal enrollment. Sable's pipeline.add fires its
        // mass-tracker build as part of enrollment, and the downstream handleBlockChange
        // cascade we trigger during block copy depends on the mass tracker existing.
        //
        // Wrap in withForceRedirect so any packets emitted by the tracking system's
        // observer fire (StartTracking via the additionQueue, etc.) get routed to the
        // dest dim's client container even for cross-dim viewers (players in source
        // dim looking through the portal). Without this, the initial StartTracking
        // packet lands in the wrong client container and the mirror is invisible.
        ServerSubLevel mirror;
        try {
            mirror = PacketRedirection.withForceRedirectAndGet(destLevel, () -> {
                return (ServerSubLevel) destContainer.allocateSubLevel(
                    mirrorUuid, plotXZ[0], plotXZ[1], mirrorPose
                );
            });
        } catch (Throwable t) {
            LOG.error("[IPL-MIRROR] failed to allocate mirror for source {}", sourceUuid, t);
            return null;
        }
        if (mirror == null) {
            // Shouldn't happen unless allocateSubLevel returned null instead of throwing,
            // but be defensive.
            LOG.error("[IPL-MIRROR] mirror allocation returned null for source {}", sourceUuid);
            return null;
        }

        // Mark as kinematic AFTER enrollment. updatePose's HEAD-cancel mixin reads
        // this flag to skip pose readback. Flag is checked again in syncMirrorPose
        // and in the controller's mirror-skip filter.
        if (mirror instanceof IplKinematicSubLevelHolder holder) {
            holder.ipl$setKinematicMirror(true);
        }

        // Copy blocks from source plot to mirror plot. Same algorithm as Phase 1's
        // atomic teleport block copy. handleBlockChange fires for each setBlockState,
        // cascading through Sable mass/heatmap/floating-block updates and Aero balloon
        // rebuild. Mass tracker is already built (via pipeline.add above) so this
        // works without NPE.
        int blocksCopied = SableTransitOps.copyPlotBlocksPublic(
            source.getPlot(), mirror.getPlot(),
            source.getLevel(), destLevel
        );

        // Rebuild the mass tracker now that the plot is fully populated. pipeline.add
        // initially built it from an empty plot bbox; the incremental updates via
        // handleBlockChange may not fully grow the tracker's internal structures
        // past the original (empty) bounds. A fresh rebuild from the populated plot
        // bbox produces a correct tracker that won't be flagged as invalid by
        // processSubLevelRemovals on the next tick.
        try {
            mirror.buildMassTracker();
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] post-copy buildMassTracker failed for {}", mirrorUuid, t);
            // Continue: pipeline.add's initial tracker is still in place.
        }

        // Pin the pipeline body's pose to our mapped pose now that blocks are in
        // (mass tracker fully populated). Subsequent syncMirrorPose calls will keep
        // teleporting it back if simulation tries to drift it.
        pinPipelinePose(destContainer, mirror, mirrorPose);

        // Register the mirror.
        MirrorRegistry.put(new MirrorRegistry.MirrorEntry(
            sourceUuid, portalUuid, mirrorUuid,
            source.getLevel().dimension(), destLevel.dimension()
        ));

        LOG.info(
            "[IPL-MIRROR] spawned mirror={} source={} portal={} {} -> {} blocks={}",
            mirrorUuid, sourceUuid, portalUuid,
            source.getLevel().dimension().location(),
            destLevel.dimension().location(),
            blocksCopied
        );

        return mirror;
    }

    /**
     * Update the mirror's logical pose from the source's, via the portal transform.
     * Also re-pins the pipeline body's pose (which would otherwise drift each substep
     * since the pipeline keeps simulating the mirror). Returns true on success.
     */
    public static boolean syncMirrorPose(ServerSubLevel source, Portal portal, MirrorRegistry.MirrorEntry entry) {
        MinecraftServer server = source.getLevel().getServer();
        if (server == null) return false;

        ServerLevel destLevel = server.getLevel(entry.destDim());
        if (destLevel == null) return false;

        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) return false;

        var subLevel = destContainer.getSubLevel(entry.mirrorUuid());
        if (!(subLevel instanceof ServerSubLevel mirror) || mirror.isRemoved()) {
            return false;
        }

        Pose3d newPose = SableTransitOps.computeMappedPose(source.logicalPose(), portal);
        mirror.logicalPose().set(newPose);
        pinPipelinePose(destContainer, mirror, newPose);
        return true;
    }

    /**
     * Remove a mirror sub-level and unregister it. Idempotent -- calling on an
     * already-despawned mirror is harmless.
     */
    public static void despawnMirror(MirrorRegistry.MirrorEntry entry, MinecraftServer server) {
        MirrorRegistry.remove(entry.sourceUuid(), entry.portalUuid());

        if (server == null) return;
        ServerLevel destLevel = server.getLevel(entry.destDim());
        if (destLevel == null) return;

        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) return;

        var subLevel = destContainer.getSubLevel(entry.mirrorUuid());
        if (subLevel == null) return;

        // Wrap removeSubLevel in withForceRedirect so Sable's observer chain (which
        // includes SubLevelTrackingSystem.onSubLevelRemoved -> sendRemoval to tracking
        // players) emits StopTracking packets tagged with the dest dim. Without this,
        // cross-dim viewers (players in source dim looking through the portal) receive
        // the StopTracking in their CURRENT dim's container -- which doesn't have the
        // mirror -- so the client's dest-dim container retains a stale copy. Over
        // repeated spawn/despawn cycles, stale mirrors accumulate and eventually
        // trigger "Plot already exists" on the client.
        ServerSubLevel finalSubLevel = (ServerSubLevel) subLevel;
        try {
            PacketRedirection.withForceRedirect(destLevel, () -> {
                destContainer.removeSubLevel(finalSubLevel, SubLevelRemovalReason.REMOVED);
            });
            LOG.info("[IPL-MIRROR] despawned mirror={} source={}",
                entry.mirrorUuid(), entry.sourceUuid());
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] error despawning mirror={}", entry.mirrorUuid(), t);
        }
    }

    /**
     * Teleport the mirror's body in the physics pipeline to the given pose. Called
     * on spawn and on every {@link #syncMirrorPose} so the pipeline body never drifts
     * away from our externally-controlled pose -- otherwise gravity and other
     * accumulating forces would slowly nudge the pipeline body off, and any code
     * that queries pipeline state directly would see the wrong location.
     */
    private static void pinPipelinePose(ServerSubLevelContainer destContainer, ServerSubLevel mirror, Pose3d pose) {
        try {
            SubLevelPhysicsSystem physics = destContainer.physicsSystem();
            if (physics == null) return;
            PhysicsPipeline pipeline = physics.getPipeline();
            if (pipeline == null) return;
            pipeline.teleport(mirror, pose.position(), pose.orientation());
        } catch (Throwable t) {
            // Don't let pipeline issues kill mirror sync. Logged at debug; if it
            // becomes a problem we'll surface it.
            LOG.debug("[IPL-MIRROR] pipeline.teleport failed for mirror={}", mirror.getUniqueId(), t);
        }
    }
}
