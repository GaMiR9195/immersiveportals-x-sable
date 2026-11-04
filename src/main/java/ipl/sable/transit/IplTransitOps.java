package ipl.sable.transit;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.platform.SableAssemblyPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Transit primitives shared by the flip ({@link IplShipFlip}) and the controller: the
 * portal-mapped pose, the plot-to-plot block copy of a cross-dimension move, a free plot
 * slot, and the stale-actor rebind.
 */
public final class IplTransitOps {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    private IplTransitOps() {}

    /**
     * Rebind any STALE physics-actor registrations on {@code sub} to the live block
     * entity currently in the chunk.
     *
     * <p><b>Why:</b> after a transit, the dest chunk's block entities get RECREATED (a new object at the same pos)
     * by a chunk reprocess that does not re-fire {@code plot.onBlockChange}. Sable's actor
     * registry ({@code blockEntityActors}, a {@code pos -> BlockEntity} map) is populated at
     * copy time and keeps pointing at the OLD, now-orphaned block entity. The physics loop
     * iterates {@code getBlockEntityActors()} and ticks that stale object -- whose
     * {@code rotationSpeed} is frozen at 0 because it isn't the one in the world that ticks
     * -- so propellers read {@code active=false} and apply no thrust, even though the live
     * chunk BE is on a healthy kinetic network ({@code getSpeed()=40}). A never-transited
     * airship is unaffected (its actors were registered at assembly and never swapped).
     *
     * <p>{@code plot.onBlockChange(pos, state)} re-reads {@code level.getBlockEntity}
     * and re-{@code put}s the live BE into the registry (idempotent). We only call it for
     * positions where the registered actor object differs from the live chunk BE, so for a
     * healthy airship this is a pure no-op (just identity comparisons) and is safe to run
     * every tick for every sub-level -- making it self-healing against any future swap
     * (e.g. a hold/unhold cycle when a player flies away and back), not just the transit.
     *
     * @return the number of stale actors rebound this call (0 = nothing was stale).
     */
    public static int resyncStaleActors(ServerSubLevel sub) {
        if (sub.isRemoved()) return 0;
        ServerLevelPlot plot = sub.getPlot();
        ServerLevel level = sub.getLevel();
        List<BlockPos> stale = null;
        for (var actor : plot.getBlockEntityActors()) {
            if (!(actor instanceof BlockEntity actorBe)) continue;
            BlockPos pos = actorBe.getBlockPos();
            BlockEntity chunkBe = level.getBlockEntity(pos);
            // Only treat as stale if the chunk currently holds a DIFFERENT (non-null) BE.
            // A transiently-null lookup (chunk momentarily unavailable) must NOT cause us
            // to evict a valid actor.
            if (chunkBe != null && chunkBe != actorBe) {
                if (stale == null) stale = new ArrayList<>(4);
                stale.add(pos);
            }
        }
        if (stale == null) return 0;
        for (BlockPos pos : stale) {
            try {
                plot.onBlockChange(pos, level.getBlockState(pos));
            } catch (RuntimeException e) {
                LOG.warn("[IPL-TRANSIT] failed to rebind stale actor at {} for {}",
                    pos, sub.getUniqueId(), e);
            }
        }
        return stale.size();
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
     * The first empty plot of {@code container} as local {x, z}, or null when the grid is full.
     * Sable's own {@code getFirstEmptyPlot} is private; this is the same scan over its public
     * occupancy API.
     */
    static int @Nullable [] findFirstEmptyPlot(SubLevelContainer container) {
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

    /**
     * Copy every non-air block (plus its block-entity NBT) from {@code src} plot to
     * {@code dst} plot. Returns the count of blocks copied.
     *
     * <p>Critically: each {@code chunk.setBlockState(...)} call cascades through
     * Sable's {@code SableCommonEvents.handleBlockChange} which:
     * <ul>
     *   <li>Updates the dest plot's mass tracker, heatmap, floating-block state.</li>
     *   <li>Fires Aero's {@code BalloonMap.updateNearbyBalloons} on each block change,
     *       rebuilding the dest-dim balloon (Aero hot-air-balloon controller) as the
     *       blocks land.</li>
     * </ul>
     * That cascade is the load-bearing piece: it's why we don't need to do any
     * Sable-specific or Aero-specific state copy — block placement triggers it.
     *
     * <p>Y-range handling: if the destination has a smaller build height than the source
     * (e.g. overworld -> nether for a ship above Y=128), out-of-range blocks are skipped
     * with a warning — a partial copy, as a player would expect from a low-ceiling portal.
     */
    static int copyPlotBlocks(
        ServerLevelPlot src, ServerLevelPlot dst,
        ServerLevel srcLevel, ServerLevel dstLevel
    ) {
        int blocksCopied = 0;
        int blocksSkippedOutOfYRange = 0;

        int dstMinY = dstLevel.getMinBuildHeight();
        int dstMaxY = dstLevel.getMaxBuildHeight();

        // Accumulate every placed block so we can run a SECOND notify pass after
        // all placement, exactly as Sable's own SubLevelAssemblyHelper.moveBlocks
        // does. The placement above uses setBlockState(isMoving=true), which skips
        // neighbour updates; Create relies on those updates to (re)connect kinetic
        // networks (wheels, propellers). Placing without notifying leaves the
        // copied actors registered-but-disconnected -> they tick but read zero
        // kinetic speed -> no thrust. Doing notify in a second pass (not inline)
        // matches moveBlocks and ensures all blocks exist before neighbours fire.
        record Placed(BlockPos pos, LevelChunk chunk, BlockState state) {}
        List<Placed> placed = new ArrayList<>();

        // Suppress onPlace during the bulk placement, exactly as
        // SubLevelAssemblyHelper.moveBlocks does via setIgnoreOnPlace
        // (NeoForge: Level.captureBlockSnapshots = true gates the onPlace /
        // BE / neighbour side-effects in LevelChunk.setBlockState).
        //
        // WHY THIS IS LOAD-BEARING for Create kinetics: without it, every
        // setBlockState fires onPlace on a HALF-BUILT structure, and BEFORE
        // loadWithComponents restores the block entity's kinetic NBT. Create's
        // KineticBlockEntity then attaches to a partial/empty rotation network
        // (some neighbours not placed yet) and caches that broken state; the
        // later markAndNotifyBlock neighbour-notify pass does NOT repair it, so
        // getSpeed() stays 0 -> BasePropellerBlockEntity.rotationSpeed decays to
        // 0 -> isActive() false -> no thrust. Suppressing onPlace here, then
        // notifying once the whole structure exists (second pass below), lets
        // each KineticBlockEntity rebuild its network cleanly on first tick --
        // the same clean path a world reload takes (which is exactly why a
        // reload fixes dead propellers/wheels). This mirrors moveBlocks: assembly
        // and load both avoid premature onPlace.
        SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(dstLevel, true);
        try {
            for (PlotChunkHolder srcHolder : src.getLoadedChunks()) {
                LevelChunk srcChunk = srcHolder.getChunk();
                ChunkPos srcChunkPos = srcChunk.getPos();

                // The block layout is plot-local. We want the same local layout in dst,
                // so translate the chunk position through plot coordinate spaces.
                ChunkPos localChunkPos = src.toLocal(srcChunkPos);
                ChunkPos dstChunkPos = dst.toGlobal(localChunkPos);

                // Ensure the dst plot has a chunk allocated at the corresponding position.
                if (dst.getChunkHolder(localChunkPos) == null) {
                    dst.newEmptyChunk(dstChunkPos);
                }
                LevelChunk dstChunk = dst.getChunk(localChunkPos);
                if (dstChunk == null) {
                    LOG.warn("[IPL-TRANSIT] dst chunk alloc failed at local {}", localChunkPos);
                    continue;
                }

                // The block X/Z within a chunk is independent of which global chunk it
                // is. So we can iterate by section and local (lx, ly, lz) and add the
                // src/dst chunk's min-block offset for each side.
                LevelChunkSection[] sections = srcChunk.getSections();
                int sectionsCount = srcChunk.getSectionsCount();
                for (int sectionIdx = 0; sectionIdx < sectionsCount; sectionIdx++) {
                    LevelChunkSection section = sections[sectionIdx];
                    if (section == null || section.hasOnlyAir()) continue;

                    int sectionY = srcChunk.getSectionYFromSectionIndex(sectionIdx);
                    int sectionBaseY = sectionY << 4;

                    for (int ly = 0; ly < 16; ly++) {
                        int worldY = sectionBaseY + ly;
                        if (worldY < dstMinY || worldY >= dstMaxY) {
                            // Skip blocks outside dest dim's vertical range. Count for log.
                            for (int lx = 0; lx < 16; lx++) {
                                for (int lz = 0; lz < 16; lz++) {
                                    if (!section.getBlockState(lx, ly, lz).isAir()) {
                                        blocksSkippedOutOfYRange++;
                                    }
                                }
                            }
                            continue;
                        }
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                BlockState state = section.getBlockState(lx, ly, lz);
                                if (state.isAir()) continue;

                                BlockPos srcWorldPos = new BlockPos(
                                    srcChunkPos.getMinBlockX() + lx,
                                    worldY,
                                    srcChunkPos.getMinBlockZ() + lz
                                );
                                BlockPos dstWorldPos = new BlockPos(
                                    dstChunkPos.getMinBlockX() + lx,
                                    worldY,
                                    dstChunkPos.getMinBlockZ() + lz
                                );

                                // Save BE NBT before setting state (which can destroy
                                // the source BE during the cascade).
                                BlockEntity srcBE = srcChunk.getBlockEntity(srcWorldPos);
                                CompoundTag beTag = null;
                                if (srcBE != null) {
                                    beTag = srcBE.saveWithFullMetadata(srcLevel.registryAccess());
                                    beTag.putInt("x", dstWorldPos.getX());
                                    beTag.putInt("y", dstWorldPos.getY());
                                    beTag.putInt("z", dstWorldPos.getZ());
                                }

                                // setBlockState(pos, state, isMoving) -- isMoving=true tells
                                // vanilla "skip neighbour-update side effects." Matches
                                // SubLevelAssemblyHelper.moveBlocks convention. The skipped
                                // neighbour updates are issued in the second pass below.
                                dstChunk.setBlockState(dstWorldPos, state, true);
                                blocksCopied++;
                                placed.add(new Placed(dstWorldPos, dstChunk, state));

                                // Restore BE state on dest, if any.
                                //
                                // NOTE: Sable's LevelChunkMixin already fires
                                // plot.onBlockChange on the setBlockState RETURN above
                                // (registering actors / lift-providers / reaction wheels),
                                // so we do NOT call onBlockChange ourselves here.
                                if (beTag != null) {
                                    BlockEntity dstBE = dstChunk.getBlockEntity(dstWorldPos);
                                    if (dstBE != null) {
                                        dstBE.loadWithComponents(beTag, dstLevel.registryAccess());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            // Always re-enable onPlace, even if a setBlockState above threw.
            // Leaving captureBlockSnapshots=true on the dest level would
            // silently swallow onPlace/neighbour updates for ALL future block
            // changes in that dimension -- a nasty latent corruption. moveBlocks
            // relies on per-block try/catch for this; we use try/finally so the
            // flag is guaranteed cleared. The markAndNotifyBlock pass below
            // intentionally runs with onPlace RE-ENABLED and the structure whole.
            SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(dstLevel, false);
        }

        // SECOND PASS: notify neighbours for every placed block, mirroring
        // SubLevelAssemblyHelper.moveBlocks' second loop. This issues the
        // sendBlockUpdated / blockUpdated / updateNeighbourShapes cascade that the
        // isMoving=true placement skipped -- the cascade Create uses to (re)connect
        // kinetic networks. Without it, copied wheels/propellers tick but read zero
        // kinetic speed (no thrust), intermittently depending on whether some other
        // incidental block update happened to trigger Create's rescan. Done as a
        // separate pass so ALL blocks exist before any neighbour update fires
        // (a block updating mid-copy would see half-placed neighbours).
        // Flags 3 (BLOCK_UPDATE | NOTIFY_NEIGHBORS) + recursionLeft 512 match
        // moveBlocks exactly.
        for (Placed p : placed) {
            try {
                SubLevelAssemblyHelper.markAndNotifyBlock(
                    dstLevel, p.pos(), p.chunk(),
                    Blocks.AIR.defaultBlockState(),
                    p.state(), 3, 512
                );
            } catch (RuntimeException e) {
                LOG.warn("[IPL-TRANSIT] markAndNotifyBlock failed at {} -- kinetic reconnect "
                    + "may be incomplete for this block", p.pos(), e);
            }
        }

        // THIRD PASS: register block-entity tickers + start ticking each dest chunk,
        // exactly as the world-LOAD path does (ServerLevelPlot.load lines 551-552:
        // chunk.registerAllBlockEntitiesAfterLevelLoad(); level.startTickingChunk(chunk)).
        //
        // WHY THIS IS THE ACTUAL FIX (proven by the T+25 actor-diag): after a transit the
        // copied propeller has getSpeed()=40, hasNetwork=true, hasSource=true -- the Create
        // kinetic network IS reconnected (RotationPropagator wires it up during placement,
        // without needing the BE's own tick). But the propeller reports active=false /
        // thrust=0 because its rotationSpeed field, updated ONLY inside
        // BasePropellerBlockEntity.tick() via updateRotationSpeed(), never advances --
        // i.e. the block entity's tick() is not running on the dest. The chunk was created
        // empty (newEmptyChunk -> addChunkHolder calls registerAllBlockEntitiesAfterLevelLoad
        // on a chunk with NO block entities yet), and the BEs we then setBlockState in never
        // got entered into the level's block-entity tick loop. A world reload fixes it
        // precisely because load re-runs registerAllBlockEntitiesAfterLevelLoad AFTER the
        // BEs exist, then startTickingChunk. We replicate that here. Idempotent:
        // registerAllBlockEntitiesAfterLevelLoad rebinds tickers via tickersInLevel.compute
        // keyed by pos, so re-running it over already-present BEs is safe.
        Set<LevelChunk> tickChunks =
            Collections.newSetFromMap(new IdentityHashMap<>());
        for (Placed p : placed) tickChunks.add(p.chunk());
        for (LevelChunk ch : tickChunks) {
            try {
                ch.registerAllBlockEntitiesAfterLevelLoad();
                dstLevel.startTickingChunk(ch);
            } catch (RuntimeException e) {
                LOG.warn("[IPL-TRANSIT] failed to (re)register block-entity tickers for dest "
                    + "chunk {} -- copied machinery may not tick", ch.getPos(), e);
            }
        }

        if (blocksSkippedOutOfYRange > 0) {
            LOG.warn(
                "[IPL-TRANSIT] {} blocks skipped because they fell outside dest dim {} Y range [{}, {})",
                blocksSkippedOutOfYRange,
                dstLevel.dimension().location(),
                dstMinY, dstMaxY
            );
        }

        return blocksCopied;
    }
}
