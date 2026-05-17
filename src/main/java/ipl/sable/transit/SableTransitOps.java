package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.BitSet;
import java.util.UUID;

/**
 * Static transit primitives for Phase 1: atomic teleport of a {@link ServerSubLevel}
 * through an IP {@link Portal}.
 *
 * <p>"Atomic" here means: in a single server tick, the source sub-level is removed
 * from its dimension and a new sub-level with the same UUID is allocated in the
 * destination dimension at the portal-mapped pose. Phase 1 leaves the dest plot
 * empty (block copy is Commit 2). Riders are not yet teleported (Commit 3).
 *
 * <p>All operations here are pure — they read sub-level state, mutate Sable containers
 * via the documented API, and return. The orchestrator that decides <i>when</i> to
 * fire is {@link SableTransitController}.
 */
public final class SableTransitOps {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    private SableTransitOps() {}

    /**
     * Execute an atomic transit. Returns true if the transit completed; false if it
     * was aborted (e.g., dest plot grid full, dest dim has no container, etc.).
     *
     * <p>Order of operations is deliberate:
     * <ol>
     *   <li>Resolve destination level + container. Bail if missing.</li>
     *   <li>Find a free plot in destination container. Bail if grid full.</li>
     *   <li>Compute the portal-mapped pose for the destination.</li>
     *   <li>Allocate destination sub-level (reuses source UUID).</li>
     *   <li>Remove source sub-level. Cascades through Sable's observers
     *       ({@code SubLevelTrackingSystem.onSubLevelRemoved} -> sendRemoval to
     *       all source-dim trackers; {@code SubLevelPhysicsSystem.onSubLevelRemoved}
     *       -> physics body removed).</li>
     * </ol>
     *
     * <p>Block copy and rider teleport are Commits 2 and 3 of Phase 1 respectively;
     * placeholders in this method just log that the steps would run.
     */
    public static boolean executeTransit(ServerSubLevel source, Portal portal) {
        MinecraftServer server = source.getLevel().getServer();
        if (server == null) {
            LOG.warn("[IPL-TRANSIT] aborted: source level has no server (sourceUuid={})", source.getUniqueId());
            return false;
        }

        ServerLevel destLevel = server.getLevel(portal.getDestDim());
        if (destLevel == null) {
            LOG.warn("[IPL-TRANSIT] aborted: destination dim {} not loaded (sourceUuid={})",
                portal.getDestDim().location(), source.getUniqueId());
            return false;
        }

        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) {
            LOG.warn("[IPL-TRANSIT] aborted: destination dim {} has no SubLevelContainer (sourceUuid={})",
                destLevel.dimension().location(), source.getUniqueId());
            return false;
        }

        int[] plotXZ = findFirstEmptyPlot(destContainer);
        if (plotXZ == null) {
            LOG.warn("[IPL-TRANSIT] aborted: destination dim {} plot grid full (sourceUuid={})",
                destLevel.dimension().location(), source.getUniqueId());
            return false;
        }

        Pose3d destPose = computeMappedPose(source.logicalPose(), portal);
        UUID uuid = source.getUniqueId();

        ServerSubLevelContainer sourceContainer = (ServerSubLevelContainer)
            SubLevelContainer.getContainer(source.getLevel());
        if (sourceContainer == null) {
            LOG.warn("[IPL-TRANSIT] aborted: source level has no SubLevelContainer (uuid={})", uuid);
            return false;
        }

        LOG.info("[IPL-TRANSIT] firing  uuid={}  {} -> {}  destPlot=({},{})  destPos=({},{},{})",
            uuid,
            source.getLevel().dimension().location(),
            destLevel.dimension().location(),
            plotXZ[0], plotXZ[1],
            destPose.position().x(), destPose.position().y(), destPose.position().z());

        // 4. Allocate dest sub-level.
        ServerSubLevel dest;
        try {
            dest = (ServerSubLevel) destContainer.allocateSubLevel(uuid, plotXZ[0], plotXZ[1], destPose);
        } catch (Throwable t) {
            LOG.error("[IPL-TRANSIT] failed to allocate destination sub-level for uuid={}", uuid, t);
            return false;
        }

        // 5. Commit-2 placeholder: copy blocks.
        copyPlotBlocksStub(source.getPlot(), dest.getPlot());

        // 6. Commit-3 placeholder: teleport riders.
        teleportRidersStub(source, portal, destLevel);

        // 7. Remove source. This fires SubLevelObserver.onSubLevelRemoved synchronously;
        //    SubLevelTrackingSystem emits a StopTracking to all source-dim trackers.
        try {
            sourceContainer.removeSubLevel(source, SubLevelRemovalReason.REMOVED);
        } catch (Throwable t) {
            // If source removal fails, we've already allocated dest -- that's a bad state.
            // Try to roll back dest. If that also fails, log loudly.
            LOG.error("[IPL-TRANSIT] failed to remove source for uuid={}; attempting dest rollback", uuid, t);
            try {
                destContainer.removeSubLevel(dest, SubLevelRemovalReason.REMOVED);
            } catch (Throwable t2) {
                LOG.error("[IPL-TRANSIT] rollback of dest also failed for uuid={}", uuid, t2);
            }
            return false;
        }

        LOG.info("[IPL-TRANSIT] complete  uuid={}", uuid);
        return true;
    }

    /**
     * Maps a sub-level's source-dim pose to the destination-dim pose via the portal's
     * transformation.
     *
     * <p>Position: {@code portal.transformPoint(sourcePos)}.
     * <p>Orientation: {@code portalRotation * sourceOrientation} (apply source first,
     * then portal). The rotationPoint and scale are passed through unchanged.
     */
    public static Pose3d computeMappedPose(Pose3d sourcePose, Portal portal) {
        // Position via the portal transform.
        Vec3 sourcePos = new Vec3(
            sourcePose.position().x(), sourcePose.position().y(), sourcePose.position().z()
        );
        Vec3 destPos = portal.transformPoint(sourcePos);

        // Orientation: compose portal rotation with source orientation.
        DQuaternion portalRot = portal.getRotationD();
        Quaterniond portalJoml = new Quaterniond(portalRot.x, portalRot.y, portalRot.z, portalRot.w);
        Quaterniond destOrient = new Quaterniond(portalJoml).mul(sourcePose.orientation());

        Pose3d destPose = new Pose3d();
        destPose.position().set(destPos.x, destPos.y, destPos.z);
        destPose.orientation().set(destOrient);
        // rotationPoint is typically the center of mass in plot-local coords;
        // shouldn't change when transiting dims. Copy through.
        destPose.rotationPoint().set(sourcePose.rotationPoint());
        // Scale is also pose-local; copy.
        destPose.scale().set(sourcePose.scale());
        return destPose;
    }

    /**
     * Find the first empty plot in a container by scanning occupancy bits. Returns
     * {x, z} local plot coordinates, or null if the grid is full.
     *
     * <p>Sable's container has its own {@code getFirstEmptyPlot} but it's private.
     * The pieces we need ({@code getOccupancy}, {@code getLogSideLength},
     * {@code getIndex}) are public, so we just replicate the algorithm.
     */
    private static int @Nullable [] findFirstEmptyPlot(SubLevelContainer container) {
        int sideLength = 1 << container.getLogSideLength();
        BitSet occupancy = container.getOccupancy();
        for (int x = 0; x < sideLength; x++) {
            for (int z = 0; z < sideLength; z++) {
                if (!occupancy.get(container.getIndex(x, z))) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Commit 2 / 3 stubs
    // -------------------------------------------------------------------------

    /**
     * Commit 2 will implement plot-to-plot block copy. For Commit 1, this is a
     * no-op stub so we can validate the lifecycle plumbing in isolation: dest
     * sub-level will be allocated with an empty plot and players in dest dim will
     * see an empty sub-level (visible via debug commands but not visually rendered).
     */
    private static void copyPlotBlocksStub(ServerLevelPlot src, ServerLevelPlot dst) {
        // Intentionally empty for Commit 1.
    }

    /**
     * Commit 3 will implement rider teleport via
     * {@code ServerTeleportationManager.teleportEntityGeneral}. For Commit 1, this
     * is a no-op stub. Riders standing on the airship during a transit will be
     * left in source dim, which is fine for the Commit 1 validation scenario
     * (we'll fly the airship through unmanned).
     */
    private static void teleportRidersStub(ServerSubLevel source, Portal portal, ServerLevel destLevel) {
        // Intentionally empty for Commit 1.
    }
}
