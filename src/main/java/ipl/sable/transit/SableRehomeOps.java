package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dim-agnostic phase 2 (REFACTOR_SPEC.md section 19.3 Option B): rehome every
 * {@link ServerSubLevel} from its parent dimension into the dedicated hosting dimension
 * {@code ipl_sable:sublevels}, and (phase 6) flip {@code parentLevel} on portal crossings
 * instead of copying plot blocks across dimensions.
 *
 * <p><b>Rehoming (migrate-at-assembly):</b> Sable's assembly/load paths are left completely
 * untouched — a sub-level is born embedded in its parent dim exactly as stock Sable creates it
 * (this is what fixed the v1 {@code assembleBlocks} blocker). One container-tick later, the
 * sweep notices an un-hosted sub-level and atomically: allocates a same-UUID sub-level in the
 * hosting container at an identical pose, copies plot blocks + block entities with the proven
 * {@link SableTransitOps#copyPlotBlocksPublic 3-pass copy}, relocates plot-resident entities
 * (item frames, seats), transfers physics velocity verbatim, stamps {@code parentLevel}, and
 * removes the parent-dim original. World coordinates never change, so riders standing on deck
 * and the visual pose are unaffected.
 *
 * <p><b>Parent restore:</b> hosted sub-levels persist their parent dim in the sub-level
 * {@code user_data} NBT ({@value #PARENT_DIM_KEY}). On world load, the duck field starts
 * out pointing at the hosting level; the sweep restores it from NBT (idempotent).
 *
 * <p><b>Hosted transit (parent flip):</b> when a hosted airship's OBB fully crosses a portal
 * in its parent dim, transit is just: remap pose through the portal, flip {@code parentLevel},
 * teleport riders, force re-track. No block copying, no container move — the unifying move.
 */
public final class SableRehomeOps {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-rehome");

    /** user_data key persisting the parent dimension id of a hosted sub-level. */
    public static final String PARENT_DIM_KEY = "ipl_parent_dim";

    private static volatile boolean loggedEnabled = false;

    private SableRehomeOps() {}

    /**
     * Per-container-tick driver, called from {@code SableSubLevelTransitMixin} right before
     * the transit controller. Cheap no-op for containers with nothing to do.
     *
     * <ul>
     *   <li>On the hosting container: restore {@code parentLevel} from user data for any
     *       freshly-deserialized sub-level.</li>
     *   <li>On every other container: rehome at most one un-hosted sub-level per tick
     *       (bounds the copy cost; assemblies are rare).</li>
     * </ul>
     */
    public static void sweep(ServerSubLevelContainer container) {
        if (!IplDimAgnostic.isEnabled()) return;
        if (!(container.getLevel() instanceof ServerLevel level)) return;

        if (!loggedEnabled) {
            loggedEnabled = true;
            LOG.info("[IPL-REHOME] dim-agnostic mode ENABLED (disable with -Dipl.sable.dimAgnostic=false)");
        }

        if (IplDimAgnostic.isHostingLevel(level)) {
            bootRestoreHosted(container, level);
            restoreParents(container, level);
            return;
        }

        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(level.getServer());
        if (hosting == null) return; // dim not loaded; phase-0 kill-switch already warned

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel airship)) continue;
            if (airship.isRemoved()) continue;
            if (isMirror(airship)) continue;

            try {
                rehome(airship, container, level, hosting);
            } catch (Throwable t) {
                LOG.error("[IPL-REHOME] failed to rehome uuid={} from {}; leaving in legacy model",
                    airship.getUniqueId(), level.dimension().location(), t);
            }
            break; // at most one rehome per container tick
        }
    }

    private static boolean bootRestoreDone = false;

    /** Reset on server stop so singleplayer relaunch re-restores. */
    public static void resetBootRestore() {
        bootRestoreDone = false;
    }

    /**
     * One-shot eager restore of every held sub-level in the hosting dimension.
     *
     * <p>Sable restores held sub-levels when the container level loads a chunk at their
     * holding position. In the hosting dim those chunk loads only happen via physics
     * tickets of already-live ships — at a fresh boot there are none, so nothing ever
     * restores (and a NEW ship flying near an OLD save's holding position produced
     * surprise "ghost" restores). Here we enumerate the holding region files on disk
     * (r.&lt;regionX&gt;.&lt;regionZ&gt;) and feed every covered chunk position through the
     * public {@code updateChunkStatus(pos, loaded=true)} API; the readiness bypass
     * ({@code IplHostedHoldingReadinessMixin}) then releases everything on the next
     * {@code processChanges}.
     */
    private static void bootRestoreHosted(ServerSubLevelContainer container, ServerLevel hosting) {
        if (bootRestoreDone) return;
        bootRestoreDone = true;

        try {
            java.nio.file.Path dimFolder = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(
                hosting.dimension(),
                hosting.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            java.nio.file.Path subLevelsFolder = dimFolder.resolve("sublevels");
            if (!java.nio.file.Files.isDirectory(subLevelsFolder)) {
                LOG.info("[IPL-BOOT-RESTORE] no holding storage at {} (nothing saved yet)", subLevelsFolder);
                return;
            }

            // Holding-chunk region files are "r.<regionX>.<regionZ>" (a numeric third
            // component means a sub-level storage file — skip those).
            java.util.regex.Pattern regionPattern =
                java.util.regex.Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)(?:\\.[a-zA-Z]\\w*)?$");

            int regions = 0;
            var holdingMap = container.getHoldingChunkMap();
            try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.list(subLevelsFolder)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                    java.util.regex.Matcher m = regionPattern.matcher(file.getFileName().toString());
                    if (!m.matches()) continue;
                    regions++;
                    int regionX = Integer.parseInt(m.group(1));
                    int regionZ = Integer.parseInt(m.group(2));
                    for (int cx = 0; cx < 32; cx++) {
                        for (int cz = 0; cz < 32; cz++) {
                            holdingMap.updateChunkStatus(
                                new net.minecraft.world.level.ChunkPos(
                                    (regionX << 5) + cx, (regionZ << 5) + cz), true);
                        }
                    }
                }
            }

            int before = container.getAllSubLevels().size();
            holdingMap.processChanges();
            int released = container.getAllSubLevels().size() - before;
            LOG.info("[IPL-BOOT-RESTORE] scanned {} region file(s), released {} held sub-level(s)",
                regions, released);
        } catch (Throwable t) {
            LOG.error("[IPL-BOOT-RESTORE] failed", t);
        }
    }

    /**
     * Restore the duck {@code parentLevel} from persisted user data for hosted sub-levels whose
     * parent is unset (== hosting level, the constructor default after deserialization).
     */
    private static void restoreParents(ServerSubLevelContainer container, ServerLevel hosting) {
        MinecraftServer server = hosting.getServer();
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel hosted)) continue;
            if (hosted.isRemoved()) continue;

            IplSubLevelDuck duck = (IplSubLevelDuck) hosted;
            if (duck.ipl$getParentLevel() != null
                && !IplDimAgnostic.isHostingLevel(duck.ipl$getParentLevel())) {
                continue; // parent already resolved
            }

            CompoundTag userData = hosted.getUserDataTag();
            if (userData == null || !userData.contains(PARENT_DIM_KEY)) {
                // A sub-level SPLIT off a hosted ship is allocated directly in the hosting
                // container with no parent stamp — inherit the parent from its split source.
                UUID splitFrom = hosted.getSplitFromSubLevel();
                SubLevel splitSource = splitFrom != null ? container.getSubLevel(splitFrom) : null;
                if (splitSource != null) {
                    ServerLevel inherited = IplDimAgnostic.getServerParentLevel(splitSource);
                    if (inherited != null) {
                        stampParent(hosted, inherited, hosting);
                        LOG.info("[IPL-REHOME] split sub-level {} inherited parent {} from {}",
                            hosted.getUniqueId(), inherited.dimension().location(), splitFrom);
                        continue;
                    }
                }
                LOG.warn("[IPL-REHOME] hosted sub-level {} has no persisted parent dim; "
                    + "it will not appear in any dimension until reassigned", hosted.getUniqueId());
                continue;
            }

            ResourceKey<Level> parentKey = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(userData.getString(PARENT_DIM_KEY))
            );
            ServerLevel parent = server.getLevel(parentKey);
            if (parent == null) {
                LOG.warn("[IPL-REHOME] persisted parent dim {} for {} is not loaded",
                    parentKey.location(), hosted.getUniqueId());
                continue;
            }

            duck.ipl$setParentLevel(parent);
            duck.ipl$setHostingLevel(hosting);
            LOG.info("[IPL-REHOME] restored parent {} for hosted sub-level {}",
                parentKey.location(), hosted.getUniqueId());
        }
    }

    /**
     * Atomically rehome one sub-level into the hosting dimension. Mirrors
     * {@link SableTransitOps#executeTransit} step-for-step, minus the portal mapping
     * (pose/velocity transfer verbatim — world coordinates are unchanged).
     */
    private static void rehome(
        ServerSubLevel source,
        ServerSubLevelContainer sourceContainer,
        ServerLevel parentLevel,
        ServerLevel hosting
    ) {
        ServerSubLevelContainer hostingContainer = SubLevelContainer.getContainer(hosting);
        if (hostingContainer == null) {
            LOG.warn("[IPL-REHOME] hosting dim has no SubLevelContainer; skipping");
            return;
        }

        int[] plotXZ = SableTransitOps.findFirstEmptyPlotForMirror(hostingContainer);
        if (plotXZ == null) {
            LOG.warn("[IPL-REHOME] hosting plot grid full; uuid={} stays in legacy model",
                source.getUniqueId());
            return;
        }

        UUID uuid = source.getUniqueId();
        Pose3d pose = new Pose3d(source.logicalPose());
        Vector3d linVel = new Vector3d(source.latestLinearVelocity);
        Vector3d angVel = new Vector3d(source.latestAngularVelocity);

        LOG.info("[IPL-REHOME] rehoming uuid={} {} -> {} plot=({},{}) pos=({},{},{})",
            uuid, parentLevel.dimension().location(), hosting.dimension().location(),
            plotXZ[0], plotXZ[1],
            pose.position().x(), pose.position().y(), pose.position().z());

        // 1. Allocate the hosted twin (same UUID, identical pose). Physics enrolls it in the
        //    HOSTING pipeline; the block-copy cascade below populates mass/CoM before the next
        //    physics step, same proven ordering as executeTransit.
        ServerSubLevel hosted =
            (ServerSubLevel) hostingContainer.allocateSubLevel(uuid, plotXZ[0], plotXZ[1], pose);

        // 2. Stamp parent/hosting (duck + persistent NBT) BEFORE block copy, so any observer
        //    firing during the copy already sees the correct parent.
        stampParent(hosted, parentLevel, hosting);

        // 3. Copy blocks + block entities (3-pass: place, notify, register tickers).
        int blocksCopied = SableTransitOps.copyPlotBlocksPublic(
            source.getPlot(), hosted.getPlot(), parentLevel, hosting);

        // 4. Relocate plot-resident entities (item frames, seats, hanging entities moved into
        //    the plot at assembly). Without this, removeSubLevel(REMOVED) deletes them.
        int entitiesMoved = relocatePlotEntities(source, hosted, parentLevel, hosting);

        // 5. Transfer physics velocity verbatim (no portal rotation — same world frame).
        if (linVel.lengthSquared() > 1e-8 || angVel.lengthSquared() > 1e-8) {
            PhysicsPipeline pipeline = hostingContainer.physicsSystem().getPipeline();
            pipeline.addLinearAndAngularVelocity(hosted, linVel, angVel);
        }

        // 6. Remove the parent-dim original. Tracking emits StopTracking to its trackers (their
        //    clients drop the parent-dim copy); the hosted twin re-syncs them into the
        //    sublevels-dim client container on the next hosting-container tick.
        try {
            sourceContainer.removeSubLevel(source, SubLevelRemovalReason.REMOVED);
        } catch (Throwable t) {
            LOG.error("[IPL-REHOME] failed to remove parent-dim original uuid={}; "
                + "rolling back hosted twin", uuid, t);
            try {
                hostingContainer.removeSubLevel(hosted, SubLevelRemovalReason.REMOVED);
            } catch (Throwable t2) {
                LOG.error("[IPL-REHOME] rollback of hosted twin also failed for uuid={}", uuid, t2);
            }
            return;
        }

        LOG.info("[IPL-REHOME] complete uuid={} blocks={} entities={}", uuid, blocksCopied, entitiesMoved);
    }

    /**
     * Hosted cross-portal transit: the parent flip. Pose is remapped through the portal,
     * velocities rotated, riders teleported, {@code parentLevel} flipped, trackers reset.
     * Plot chunks do not move.
     *
     * @return true if the flip completed.
     */
    public static boolean executeHostedTransit(ServerSubLevel hosted, Portal portal) {
        ServerLevel hosting = hosted.getLevel();
        MinecraftServer server = hosting.getServer();
        if (server == null) return false;

        ServerLevel oldParent = IplDimAgnostic.getServerParentLevel(hosted);
        if (oldParent == null) {
            LOG.warn("[IPL-FLIP] aborted: hosted sub-level {} has no resolvable parent", hosted.getUniqueId());
            return false;
        }

        ServerLevel newParent = server.getLevel(portal.getDestDim());
        if (newParent == null || IplDimAgnostic.isHostingLevel(newParent)) {
            LOG.warn("[IPL-FLIP] aborted: invalid destination dim {} for {}",
                portal.getDestDim().location(), hosted.getUniqueId());
            return false;
        }

        ServerSubLevelContainer hostingContainer = SubLevelContainer.getContainer(hosting);
        if (hostingContainer == null) return false;
        PhysicsPipeline pipeline = hostingContainer.physicsSystem().getPipeline();

        UUID uuid = hosted.getUniqueId();
        Pose3d mappedPose = SableTransitOps.computeMappedPose(hosted.logicalPose(), portal);

        LOG.info("[IPL-FLIP] firing uuid={} {} -> {} destPos=({},{},{})",
            uuid, oldParent.dimension().location(), newParent.dimension().location(),
            mappedPose.position().x(), mappedPose.position().y(), mappedPose.position().z());

        // Capture + remap velocities through the portal rotation.
        Vector3d lin = pipeline.getLinearVelocity(hosted, new Vector3d());
        Vector3d ang = pipeline.getAngularVelocity(hosted, new Vector3d());
        Vec3 mappedLin = portal.transformLocalVec(new Vec3(lin.x, lin.y, lin.z));
        Vec3 mappedAng = portal.transformLocalVec(new Vec3(ang.x, ang.y, ang.z));

        // Teleport riders BEFORE moving the pose, while the deck is still under them.
        int riders = teleportRiders(hosted, oldParent, newParent, portal);

        // Move the physics body + logical pose to the mapped frame.
        // NOTE: terrain sections from the OLD parent stay in the hosting pipeline until their
        // tickets expire (~20 ticks). With nether 8:1 coordinate compression those can briefly
        // overlap the arrival position; accepted for now, revisit if it causes arrival bumps.
        pipeline.teleport(hosted, mappedPose.position(), mappedPose.orientation());
        hosted.logicalPose().set(mappedPose);
        pipeline.resetVelocity(hosted);
        pipeline.addLinearAndAngularVelocity(hosted,
            new Vector3d(mappedLin.x, mappedLin.y, mappedLin.z),
            new Vector3d(mappedAng.x, mappedAng.y, mappedAng.z));

        // Flip the parent.
        stampParent(hosted, newParent, hosting);

        hosted.updateBoundingBox();
        if (ipl.sable.dim.IplSceneOwnership.isEnabled()) {
            // Per-scene model: the authority swap IS a scene migration — the body (and its
            // plot voxel sections) move from the old parent's scene to the new parent's,
            // carrying the already-mapped pose and velocities. No terrain rebake: each
            // scene's terrain is native and was never wrong.
            net.minecraft.server.level.ServerLevel from =
                ipl.sable.dim.IplSceneOwnership.getBodyHome(hosted) != null
                    ? ipl.sable.dim.IplSceneOwnership.getBodyHome(hosted) : oldParent;
            ipl.sable.dim.IplSceneOwnership.migrate(hosted, from, newParent);
        } else {
            // Legacy single-hosting-scene model: force re-bake the hosting scene's terrain
            // at the ARRIVAL region from the NEW parent. Sections there may still hold the
            // OLD parent's content (especially with nether-portal coordinate overlap), and
            // the ticket manager never re-reads sections whose tickets the ship keeps
            // refreshing — without this the ship rests on a phantom copy of the old
            // dimension's terrain ("standing on air" after crossing).
            rebakeTerrainAround(hosted, newParent, pipeline);
        }

        // Force re-track: drop every tracker with a StopTracking stamped to the hosting dim
        // (their client removes the sub-level from the sublevels container cleanly). The
        // tracking sweep re-bootstraps current viewers next tick with the fresh pose — avoiding
        // a cross-dimension interpolation streak on clients that keep viewing through the portal.
        List<UUID> trackers = new ArrayList<>(hosted.getTrackingPlayers());
        if (!trackers.isEmpty()) {
            long plotCoord = subLevelPlotCoord(hosted, hostingContainer);
            PacketRedirection.withForceRedirect(hosting, () -> {
                for (UUID trackerUuid : trackers) {
                    ServerPlayer player = server.getPlayerList().getPlayer(trackerUuid);
                    if (player != null) {
                        player.connection.send(new ClientboundCustomPayloadPacket(
                            new ClientboundStopTrackingSubLevelPacket(plotCoord)));
                    }
                }
            });
            hosted.getTrackingPlayers().clear();
        }

        LOG.info("[IPL-FLIP] complete uuid={} riders={}", uuid, riders);
        return true;
    }

    /**
     * Overwrite the hosting pipeline's terrain voxels around {@code hosted} with fresh
     * content from {@code parent} (reads routed through {@link IplTerrainReadOverride}).
     * Unlike the ticket manager's enrollment, this re-bakes EXISTING sections too.
     * The vertical range is extended downward to cover post-arrival settling.
     */
    private static void rebakeTerrainAround(
        ServerSubLevel hosted, ServerLevel parent, PhysicsPipeline pipeline
    ) {
        dev.ryanhcode.sable.companion.math.BoundingBox3d b =
            new dev.ryanhcode.sable.companion.math.BoundingBox3d();
        b.set(hosted.boundingBox());
        b.expand(4.0, b);
        dev.ryanhcode.sable.companion.math.BoundingBox3i chunkBounds = b.chunkBoundsFrom();

        IplTerrainReadOverride.set(parent);
        try {
            for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
                for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                    net.minecraft.world.level.chunk.LevelChunk parentChunk;
                    try {
                        parentChunk = parent.getChunk(x, z);
                    } catch (Throwable t) {
                        continue;
                    }
                    // -2 sections below covers the immediate post-arrival fall/settle.
                    for (int y = chunkBounds.minY() - 2; y <= chunkBounds.maxY(); y++) {
                        int parentIndex = parent.getSectionIndexFromSectionY(y);
                        if (parentIndex < 0 || parentIndex >= parent.getSectionsCount()) continue;
                        pipeline.handleChunkSectionAddition(
                            parentChunk.getSection(parentIndex), x, y, z, false);
                    }
                }
            }
        } finally {
            IplTerrainReadOverride.clear();
        }
        LOG.info("[IPL-FLIP] re-baked arrival terrain for {} from {}",
            hosted.getUniqueId(), parent.dimension().location());
    }

    /** Duck + persistent-NBT parent stamp. */
    private static void stampParent(ServerSubLevel hosted, ServerLevel parent, ServerLevel hosting) {
        IplSubLevelDuck duck = (IplSubLevelDuck) hosted;
        duck.ipl$setParentLevel(parent);
        duck.ipl$setHostingLevel(hosting);

        CompoundTag userData = hosted.getUserDataTag();
        if (userData == null) {
            userData = new CompoundTag();
            hosted.setUserDataTag(userData);
        }
        userData.putString(PARENT_DIM_KEY, parent.dimension().location().toString());
    }

    /** The encoded plot coordinate used by Sable's tracking packets (see getSubLevelLong). */
    private static long subLevelPlotCoord(ServerSubLevel subLevel, SubLevelContainer container) {
        Vector2i origin = container.getOrigin();
        ChunkPos plotPos = subLevel.getPlot().plotPos;
        return ChunkPos.asLong(plotPos.x - origin.x, plotPos.z - origin.y);
    }

    /**
     * Move entities physically living inside the source plot's chunks (offset coords in the
     * parent dim) to the corresponding position in the hosted plot. Skips players, portals,
     * and passengers (their vehicles move; vanilla re-seats passengers on its own).
     */
    private static int relocatePlotEntities(
        ServerSubLevel source, ServerSubLevel hosted,
        ServerLevel parentLevel, ServerLevel hosting
    ) {
        ServerLevelPlot srcPlot = source.getPlot();
        ServerLevelPlot dstPlot = hosted.getPlot();

        int moved = 0;
        for (PlotChunkHolder holder : new ArrayList<>(srcPlot.getLoadedChunks())) {
            ChunkPos srcChunkPos = holder.getChunk().getPos();
            ChunkPos localPos = srcPlot.toLocal(srcChunkPos);
            ChunkPos dstChunkPos = dstPlot.toGlobal(localPos);

            double dx = dstChunkPos.getMinBlockX() - srcChunkPos.getMinBlockX();
            double dz = dstChunkPos.getMinBlockZ() - srcChunkPos.getMinBlockZ();

            AABB chunkBox = new AABB(
                srcChunkPos.getMinBlockX(), parentLevel.getMinBuildHeight(), srcChunkPos.getMinBlockZ(),
                srcChunkPos.getMinBlockX() + 16.0, parentLevel.getMaxBuildHeight(), srcChunkPos.getMinBlockZ() + 16.0
            );

            for (Entity entity : parentLevel.getEntitiesOfClass(Entity.class, chunkBox)) {
                if (entity instanceof Portal) continue;
                if (entity instanceof ServerPlayer) continue;
                if (entity.isRemoved()) continue;
                if (entity.getVehicle() != null) continue;

                Vec3 newPos = entity.position().add(dx, 0.0, dz);
                try {
                    Entity result = ServerTeleportationManager.teleportEntityGeneral(entity, newPos, hosting);
                    if (result != null && !result.isRemoved()) {
                        result.setDeltaMovement(entity.getDeltaMovement());
                    }
                    moved++;
                } catch (Throwable t) {
                    LOG.warn("[IPL-REHOME] failed to relocate plot entity {} for {}",
                        entity, source.getUniqueId(), t);
                }
            }
        }
        return moved;
    }

    /**
     * Teleport entities standing on the hosted airship (bbox overlap in the PARENT dim — the
     * hosted variant of {@link SableTransitOps}'s rider teleport, which queries
     * {@code source.getLevel()} and would otherwise look in the hosting dim).
     */
    private static int teleportRiders(
        ServerSubLevel hosted, ServerLevel oldParent, ServerLevel newParent, Portal portal
    ) {
        AABB queryBbox = hosted.boundingBox().toMojang().inflate(0.5);
        List<Entity> entities = oldParent.getEntitiesOfClass(Entity.class, queryBbox);

        int teleported = 0;
        for (Entity entity : entities) {
            if (entity instanceof Portal) continue;
            if (entity.isRemoved()) continue;
            if (entity.getVehicle() != null) continue;

            Vec3 mappedPos = portal.transformPoint(entity.position());
            Vec3 mappedDelta = portal.transformLocalVec(entity.getDeltaMovement());

            try {
                Entity result = ServerTeleportationManager.teleportEntityGeneral(entity, mappedPos, newParent);
                if (result != null && !result.isRemoved()) {
                    result.setDeltaMovement(mappedDelta);
                }
                teleported++;
            } catch (Throwable t) {
                LOG.warn("[IPL-FLIP] failed to teleport rider {} during flip of {}",
                    entity, hosted.getUniqueId(), t);
            }
        }
        return teleported;
    }

    /** Mirror detection shared with the transit controller. */
    static boolean isMirror(ServerSubLevel subLevel) {
        if (subLevel instanceof ipl.sable.iface.IplKinematicSubLevelHolder kh && kh.ipl$isKinematicMirror()) {
            return true;
        }
        CompoundTag userData = subLevel.getUserDataTag();
        return userData != null && userData.getBoolean("ipl_mirror");
    }
}
