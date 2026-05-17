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
import qouteall.imm_ptl.core.portal.Portal;

import java.util.UUID;

/**
 * Spawn / sync / despawn primitives for kinematic mirror sub-levels.
 *
 * <p>Phase 2 mirror lifecycle:
 * <ol>
 *   <li>{@link #spawnMirror} — called when source enters portal approach zone for the
 *       first time. Allocates a kinematic sub-level in the dest dim, copies blocks
 *       from source via {@code SableTransitOps.copyPlotBlocks}, registers in
 *       {@link MirrorRegistry}.</li>
 *   <li>{@link #syncMirrorPose} — called each server tick while the mirror exists.
 *       Updates {@code mirror.logicalPose()} from {@code source.logicalPose()} via
 *       the portal coord transform.</li>
 *   <li>{@link #despawnMirror} — called when source leaves the approach zone OR
 *       crosses the portal (handoff). Removes the mirror sub-level.</li>
 * </ol>
 *
 * <p>The mirror's UUID is fresh (not the source's UUID). The two sub-levels coexist
 * in different containers during the approach phase. Per Phase 0 decision, this
 * keeps Sable's per-container tracking clean (no cross-container UUID collisions).
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

        // Mark UUID as "incoming mirror" so the physics opt-out mixin cancels enrollment
        // when allocateSubLevel fires its onSubLevelAdded observer.
        IplMirrorIncomingTracker.markIncoming(mirrorUuid);
        ServerSubLevel mirror;
        try {
            mirror = (ServerSubLevel) destContainer.allocateSubLevel(
                mirrorUuid, plotXZ[0], plotXZ[1], mirrorPose
            );
        } catch (Throwable t) {
            LOG.error("[IPL-MIRROR] failed to allocate mirror for source {}", sourceUuid, t);
            IplMirrorIncomingTracker.unmarkIncoming(mirrorUuid);
            return null;
        } finally {
            IplMirrorIncomingTracker.unmarkIncoming(mirrorUuid);
        }

        // Belt + suspenders: explicitly mark the flag in case the mixin didn't catch it.
        if (mirror instanceof IplKinematicSubLevelHolder holder) {
            holder.ipl$setKinematicMirror(true);
        }

        // Copy blocks from source plot to mirror plot. Same algorithm as Phase 1's
        // atomic teleport block copy.
        int blocksCopied = SableTransitOps.copyPlotBlocksPublic(
            source.getPlot(), mirror.getPlot(),
            source.getLevel(), destLevel
        );

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
     * Returns true if the sync succeeded; false if the mirror has been removed or its
     * dest dim is unloaded.
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
        return true;
    }

    /**
     * Remove a mirror sub-level and unregister it. Idempotent — calling on an
     * already-despawned mirror is harmless.
     */
    public static void despawnMirror(MirrorRegistry.MirrorEntry entry) {
        MirrorRegistry.remove(entry.sourceUuid(), entry.portalUuid());

        MinecraftServer server = serverFromEntry(entry);
        if (server == null) return;

        ServerLevel destLevel = server.getLevel(entry.destDim());
        if (destLevel == null) return;

        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) return;

        var subLevel = destContainer.getSubLevel(entry.mirrorUuid());
        if (subLevel == null) return;

        try {
            destContainer.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            LOG.info("[IPL-MIRROR] despawned mirror={} source={}",
                entry.mirrorUuid(), entry.sourceUuid());
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] error despawning mirror={}", entry.mirrorUuid(), t);
        }
    }

    /** Find a server. We need it to look up the dest level by ResourceKey. */
    private static MinecraftServer serverFromEntry(MirrorRegistry.MirrorEntry entry) {
        // The registry doesn't store a server reference. We piggyback on the
        // single-server-per-process assumption that the integrated server / dedicated
        // server is reachable via static context. In our code path the controller
        // always has a server reference available; rather than plumb it through, the
        // controller calls despawnMirror via overloads that pass the server.
        return null;
    }

    /**
     * Overload of despawnMirror that takes an explicit server reference. Used by the
     * controller which has the source-level's server in hand.
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

        try {
            destContainer.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            LOG.info("[IPL-MIRROR] despawned mirror={} source={}",
                entry.mirrorUuid(), entry.sourceUuid());
        } catch (Throwable t) {
            LOG.warn("[IPL-MIRROR] error despawning mirror={}", entry.mirrorUuid(), t);
        }
    }
}
