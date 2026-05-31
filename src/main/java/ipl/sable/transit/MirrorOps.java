package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
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
        // Allocate WITHOUT physics enrollment. The allocation guard tells
        // SableMirrorPhysicsSystemMixin.onSubLevelAdded (which fires synchronously
        // inside allocateSubLevel, before we can set the per-instance kinematic
        // flag) to skip pipeline.add -- so the mirror never gets a native rigid
        // body -- while still building its mass tracker for the block-copy cascade
        // below. The tracking-system observer still runs, so the mirror is rendered
        // to clients normally.
        ServerSubLevel mirror;
        try {
            MirrorAllocationGuard.allocatingMirror = true;
            try {
                mirror = PacketRedirection.withForceRedirectAndGet(destLevel, () -> {
                    return (ServerSubLevel) destContainer.allocateSubLevel(
                        mirrorUuid, plotXZ[0], plotXZ[1], mirrorPose
                    );
                });
            } finally {
                MirrorAllocationGuard.allocatingMirror = false;
            }
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

        // Mark as kinematic now that allocation is done. The allocation guard
        // already kept it out of the pipeline; this per-instance flag is what every
        // later check reads (updatePose skip, onSubLevelRemoved skip, the
        // ownership-guard fallbacks, the controller's mirror-skip filter).
        if (mirror instanceof IplKinematicSubLevelHolder holder) {
            holder.ipl$setKinematicMirror(true);
        }

        // Tag the mirror in user_data with a persistent marker. The @Unique
        // kinematic flag above is runtime-only -- it defaults to false on a
        // fresh-from-disk load -- so we'd lose all "this is a mirror" identity
        // if a mirror somehow got persisted. user_data DOES round-trip through
        // Sable's serialise/deserialise, so a recovery mixin
        // (SableSubLevelSerializerMirrorRecoveryMixin) can recognise a
        // resurrected ghost on load and discard it before the controller
        // iterates and cascades.
        //
        // Primary defence is SableHoldingChunkMapMirrorSaveSkipMixin filtering
        // mirrors out of saveAll, so the tag should never actually be read.
        // Belt-and-suspenders: legacy saves from before that filter landed
        // (including the user's hang reproducer) still need the recovery path
        // to clean up.
        try {
            net.minecraft.nbt.CompoundTag userData = mirror.getUserDataTag();
            if (userData == null) {
                userData = new net.minecraft.nbt.CompoundTag();
                mirror.setUserDataTag(userData);
            }
            userData.putBoolean("ipl_mirror", true);
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] failed to tag mirror={} with ipl_mirror marker", mirrorUuid, t);
        }

        // Copy blocks from source plot to mirror plot. Same algorithm as Phase 1's
        // atomic teleport block copy. handleBlockChange fires for each setBlockState,
        // cascading through Sable mass/heatmap/floating-block updates and Aero balloon
        // rebuild. The mass tracker was built in onSubLevelAdded (our enrollment-skip
        // path) so getSelfMassTracker() works without NPE; the cascade's
        // pipeline.onStatsChanged call no-ops via the ownership guard since the
        // mirror has no native body.
        int blocksCopied = SableTransitOps.copyPlotBlocksPublic(
            source.getPlot(), mirror.getPlot(),
            source.getLevel(), destLevel
        );

        // Rebuild the mass tracker now that the plot is fully populated. The
        // onSubLevelAdded path built it from an empty plot bbox; the incremental
        // updates via handleBlockChange may not fully grow the tracker's internal
        // structures past the original (empty) bounds. A fresh rebuild from the
        // populated plot bbox produces a correct tracker that won't be flagged as
        // invalid by processSubLevelRemovals on the next tick.
        try {
            mirror.buildMassTracker();
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] post-copy buildMassTracker failed for {}", mirrorUuid, t);
            // Continue: the onSubLevelAdded initial tracker is still in place.
        }

        // No pipeline pose pinning: the mirror has no native rigid body, so there's
        // nothing to drift. Its pose is driven purely by logicalPose, set here from
        // the portal mapping and re-set each tick by syncMirrorPose.

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
        if (server == null) {
            LOG.warn("[IPL-MIRROR-DIAG] sync fail (server null) mirror={}", entry.mirrorUuid());
            return false;
        }

        ServerLevel destLevel = server.getLevel(entry.destDim());
        if (destLevel == null) {
            LOG.warn("[IPL-MIRROR-DIAG] sync fail (destLevel null) mirror={} destDim={}",
                entry.mirrorUuid(), entry.destDim().location());
            return false;
        }

        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) {
            LOG.warn("[IPL-MIRROR-DIAG] sync fail (destContainer null) mirror={} destDim={}",
                entry.mirrorUuid(), entry.destDim().location());
            return false;
        }

        var subLevel = destContainer.getSubLevel(entry.mirrorUuid());
        if (subLevel == null) {
            LOG.warn("[IPL-MIRROR-DIAG] sync fail (getSubLevel null) mirror={} destDim={} -- "
                + "mirror is gone from dest container; Sable removed it via some path",
                entry.mirrorUuid(), entry.destDim().location());
            return false;
        }
        if (!(subLevel instanceof ServerSubLevel mirror)) {
            LOG.warn("[IPL-MIRROR-DIAG] sync fail (not ServerSubLevel: {}) mirror={}",
                subLevel.getClass().getName(), entry.mirrorUuid());
            return false;
        }
        if (mirror.isRemoved()) {
            LOG.warn("[IPL-MIRROR-DIAG] sync fail (mirror.isRemoved=true) mirror={} -- "
                + "still in container but marked removed; something called markRemoved on it",
                entry.mirrorUuid());
            return false;
        }

        // Wrap the pose update in PacketRedirection so any movement / tracking
        // packets emitted by Sable's observers as a result of logicalPose.set get
        // tagged with the dest dim. Without this, players currently in the source
        // dim looking through the portal would receive the movement packet in their
        // CURRENT dim's client container -- which doesn't know about the mirror --
        // and the mirror would appear "stuck" while new client-side phantoms
        // accumulate.
        //
        // No pipeline teleport: the mirror has no native rigid body (it's not
        // enrolled in physics), so logicalPose IS the mirror's pose -- nothing to
        // pin back.
        Pose3d newPose = SableTransitOps.computeMappedPose(source.logicalPose(), portal);
        qouteall.imm_ptl.core.network.PacketRedirection.withForceRedirect(destLevel, () -> {
            mirror.logicalPose().set(newPose);
        });
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
            // Set the authorisation flag so SableMirrorRemovalGuardMixin lets the
            // removeSubLevel call through. Clear it in finally so a thrown exception
            // doesn't leave the flag stuck and allow subsequent unauthorised removals.
            MirrorRemovalGuard.inAuthorizedRemoval = true;
            try {
                PacketRedirection.withForceRedirect(destLevel, () -> {
                    destContainer.removeSubLevel(finalSubLevel, SubLevelRemovalReason.REMOVED);
                });
            } finally {
                MirrorRemovalGuard.inAuthorizedRemoval = false;
            }
            LOG.info("[IPL-MIRROR] despawned mirror={} source={}",
                entry.mirrorUuid(), entry.sourceUuid());
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] error despawning mirror={}", entry.mirrorUuid(), t);
        }
    }

    /**
     * Despawn a mirror that Sable's chunk-ticket manager is trying to unload
     * (its dest chunk lost its ticket). Called from
     * {@link ipl.sable.mixin.SableMirrorUnloadDespawnMixin} INSTEAD OF letting
     * {@code moveToUnloaded} run.
     *
     * <p><b>Why despawn rather than cancel:</b> the previous approach cancelled
     * {@code moveToUnloaded} at HEAD, but {@code PhysicsChunkTicketManager.update}'s
     * sub-level loop assumes the call removed the sub-level from the container --
     * it does {@code i--; continue} to re-scan the (assumed) shrunken list.
     * Cancelling left the mirror in {@code getAllSubLevels()}, so the loop
     * re-processed the same index forever: a silent infinite-loop server hang
     * (watchdog 31May, stuck at {@code PhysicsChunkTicketManager.update:135}).
     * Actually removing the mirror satisfies the loop's contract and breaks the
     * hang. It also keeps the mirror OFF disk -- a plain container removal, not the
     * holding-chunk serialize {@code moveToUnloaded} would have done (which routes
     * through {@code attemptSaveHoldingChunk}, a path our
     * {@code SubLevelStorageFile.write} chokepoint does NOT cover) -- and clears the
     * registry so the controller spawns a fresh mirror on the next approach. Brief
     * visual flicker on chunk-ticket flap is acceptable.
     *
     * <p>We hold the mirror {@code ServerSubLevel} and its level directly, so this
     * needs no {@code MinecraftServer} lookup: drop the registry entry (if any),
     * then remove from the dest container -- one uniform path for both registered
     * and orphan kinematic mirrors.
     */
    public static void despawnMirrorOnUnload(ServerSubLevel mirror) {
        UUID mirrorUuid = mirror.getUniqueId();

        MirrorRegistry.MirrorEntry entry = MirrorRegistry.getByMirrorUuid(mirrorUuid);
        if (entry != null) {
            MirrorRegistry.remove(entry.sourceUuid(), entry.portalUuid());
        }

        ServerLevel level = mirror.getLevel();
        ServerSubLevelContainer container =
            (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
        if (container == null) return;

        // Authorize past SableMirrorRemovalGuardMixin; withForceRedirect tags the
        // StopTracking with the dest dim for cross-dim viewers; plain removal (no
        // holding-chunk serialize) keeps the mirror off disk.
        MirrorRemovalGuard.inAuthorizedRemoval = true;
        try {
            PacketRedirection.withForceRedirect(level, () ->
                container.removeSubLevel(mirror, SubLevelRemovalReason.REMOVED));
            LOG.info("[IPL-MIRROR] unload-despawned mirror={} (dest chunk lost ticket)", mirrorUuid);
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] unload-despawn failed for mirror={}", mirrorUuid, t);
        } finally {
            MirrorRemovalGuard.inAuthorizedRemoval = false;
        }
    }

}
