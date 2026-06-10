package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Map;

/**
 * Cross-seam terrain cloning — physics awareness on BOTH ends of a straddled portal.
 *
 * <p>The hosting Rapier scene is the ship's reference frame (parent coordinates). While a
 * hosted sub-level straddles a portal, the DESTINATION dimension's terrain near the portal
 * is baked into that scene THROUGH THE INVERSE PORTAL TRANSFORM: a source-frame voxel at P
 * takes the dest dim's content at {@code P + offset}. The through-the-portal part of the
 * hull then collides with dest terrain exactly where it visually is — before any flip.
 *
 * <p>Scope: translation-only, block-aligned portal pairs (the standard same-orientation
 * link). Rotated portals are skipped (re-voxelizing rotated terrain is lossy; logged once).
 *
 * <p>Mechanism: per-block feeds via {@code pipeline.handleBlockChange} with
 * {@link IplTerrainReadOverride} set to (destLevel, offset) so the pipeline's neighborhood
 * re-bake also reads translated dest content. A per-(ship, portal) cache tracks fed states
 * so only deltas hit the native scene; on straddle end the fed region is restored from the
 * parent dimension.
 */
public final class IplStraddleTerrainClone {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-clone");

    /** Re-scan cadence while straddling (ticks). Block changes mid-straddle land within this. */
    private static final long RESCAN_TICKS = 10;

    /** Safety cap on fed voxels per (ship, portal). */
    private static final int MAX_FED = 50_000;

    private static final Map<MirrorRegistry.MirrorKey, CloneState> STATES = new HashMap<>();

    private static boolean loggedRotationSkip = false;

    private IplStraddleTerrainClone() {}

    private static final class CloneState {
        final java.util.UUID shipUuid;
        final ServerLevel parent;
        final ServerLevel dest;
        final PhysicsPipeline pipeline;
        final BlockPos offset;
        final Long2ObjectMap<BlockState> fed = new Long2ObjectOpenHashMap<>();
        long lastScanTick = Long.MIN_VALUE;
        boolean warnedCap = false;

        CloneState(java.util.UUID shipUuid, ServerLevel parent, ServerLevel dest,
                   PhysicsPipeline pipeline, BlockPos offset) {
            this.shipUuid = shipUuid;
            this.parent = parent;
            this.dest = dest;
            this.pipeline = pipeline;
            this.offset = offset;
        }
    }

    /** Whether any straddle session is active for this ship (server side). */
    public static boolean hasSession(java.util.UUID shipUuid) {
        for (CloneState state : STATES.values()) {
            if (state.shipUuid.equals(shipUuid)) return true;
        }
        return false;
    }

    /**
     * The mapping offset of an active straddle session of {@code sub} into
     * {@code destLevel}, or null. Used by collision pose mapping (server side).
     */
    public static BlockPos getOffsetInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level destLevel
    ) {
        for (CloneState state : STATES.values()) {
            if (state.dest == destLevel && state.shipUuid.equals(sub.getUniqueId())) {
                return state.offset;
            }
        }
        return null;
    }

    /** Called each tick a hosted ship is STRADDLING (from the transit controller). */
    public static void onStraddleTick(ServerSubLevel hosted, Portal portal, Vec3 sourceToDest) {
        if (!IplDimAgnostic.isEnabled() || !IplDimAgnostic.isHosted(hosted)) return;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(hosted);
        if (parent == null) return;
        ServerLevel dest = parent.getServer().getLevel(portal.getDestDim());
        if (dest == null || IplDimAgnostic.isHostingLevel(dest)) return;

        // Translation-only, block-aligned gate.
        DQuaternion rot = portal.getRotationD();
        if (rot != null && !isApproxIdentity(rot)) {
            if (!loggedRotationSkip) {
                loggedRotationSkip = true;
                LOG.info("[IPL-STRADDLE-CLONE] portal {} has rotation; cross-seam terrain clone "
                    + "supports translation-only pairs — skipping (logged once)", portal.getUUID());
            }
            return;
        }
        Vec3 d = portal.getDestPos().subtract(portal.getOriginPos());
        BlockPos offset = blockAligned(d);
        if (offset == null) {
            if (!loggedRotationSkip) {
                loggedRotationSkip = true;
                LOG.info("[IPL-STRADDLE-CLONE] portal {} offset {} is not block-aligned — skipping "
                    + "(logged once)", portal.getUUID(), d);
            }
            return;
        }

        ServerSubLevelContainer hostingContainer = SubLevelContainer.getContainer(hosted.getLevel());
        if (hostingContainer == null) return;

        MirrorRegistry.MirrorKey key =
            new MirrorRegistry.MirrorKey(hosted.getUniqueId(), portal.getUUID());
        CloneState state = STATES.computeIfAbsent(key, k -> {
            LOG.info("[IPL-STRADDLE-CLONE] start uuid={} portal={} offset={}",
                hosted.getUniqueId(), portal.getUUID(), offset);
            return new CloneState(hosted.getUniqueId(), parent, dest,
                hostingContainer.physicsSystem().getPipeline(), offset);
        });

        long now = parent.getGameTime();
        if (state.lastScanTick != Long.MIN_VALUE && now - state.lastScanTick < RESCAN_TICKS) {
            return;
        }
        state.lastScanTick = now;

        feedRegion(hosted, portal, sourceToDest, state);
    }

    /** Straddle ended (backed out, flipped, or left the zone): restore parent terrain. */
    public static void clear(MirrorRegistry.MirrorKey key) {
        CloneState state = STATES.remove(key);
        if (state == null) return;

        int restored = 0;
        for (Long2ObjectMap.Entry<BlockState> entry : state.fed.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            BlockState parentState = state.parent.getBlockState(pos);
            feedOne(state, pos, entry.getValue(), parentState, state.parent, null);
            restored++;
        }
        if (restored > 0) {
            LOG.info("[IPL-STRADDLE-CLONE] end — restored {} voxel(s) from {}",
                restored, state.parent.dimension().location());
        }
    }

    /** Server stopping: drop all state without native restores. */
    public static void clearAll() {
        STATES.clear();
        loggedRotationSkip = false;
    }

    private static void feedRegion(
        ServerSubLevel hosted, Portal portal, Vec3 sourceToDest, CloneState state
    ) {
        AABB box = hosted.boundingBox().toMojang().inflate(2.0);
        Vec3 origin = portal.getOriginPos();
        boolean changed = false;

        int minX = (int) Math.floor(box.minX), maxX = (int) Math.ceil(box.maxX);
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.ceil(box.maxY);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.ceil(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Dest side of the portal plane (slightly past it to close the seam).
                    double dot = (x + 0.5 - origin.x) * sourceToDest.x
                        + (y + 0.5 - origin.y) * sourceToDest.y
                        + (z + 0.5 - origin.z) * sourceToDest.z;
                    if (dot < -0.5) continue;

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockPos destPos = pos.offset(state.offset);
                    if (destPos.getY() < state.dest.getMinBuildHeight()
                        || destPos.getY() >= state.dest.getMaxBuildHeight()) continue;

                    BlockState destState = state.dest.getBlockState(destPos);
                    long posKey = pos.asLong();
                    BlockState prev = state.fed.get(posKey);
                    if (prev == destState) continue;
                    if (prev == null && destState.isAir()) continue; // nothing to add

                    if (state.fed.size() >= MAX_FED && prev == null) {
                        if (!state.warnedCap) {
                            state.warnedCap = true;
                            LOG.warn("[IPL-STRADDLE-CLONE] fed-voxel cap ({}) reached for {} — "
                                + "clone region truncated", MAX_FED, hosted.getUniqueId());
                        }
                        continue;
                    }

                    feedOne(state, pos, prev != null ? prev : Blocks.AIR.defaultBlockState(),
                        destState, state.dest, state.offset);
                    state.fed.put(posKey, destState);
                    changed = true;
                }
            }
        }

        if (changed) {
            state.pipeline.wakeUp(hosted);
        }
    }

    private static void feedOne(
        CloneState state, BlockPos pos, BlockState oldState, BlockState newState,
        ServerLevel readLevel, BlockPos readOffset
    ) {
        LevelChunkSection section = sectionFor(state.parent, pos);
        if (section == null) return;

        if (readOffset != null) {
            IplTerrainReadOverride.set(readLevel, readOffset);
        } else {
            IplTerrainReadOverride.set(readLevel);
        }
        try {
            state.pipeline.handleBlockChange(
                SectionPos.of(pos), section,
                pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15,
                oldState, newState);
        } finally {
            IplTerrainReadOverride.clear();
        }
    }

    /** A real section instance for the call (the Rapier impl ignores its content). */
    private static LevelChunkSection sectionFor(ServerLevel level, BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        int index = level.getSectionIndexFromSectionY(sectionY);
        if (index < 0 || index >= level.getSectionsCount()) return null;
        try {
            LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk.getSection(index);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isApproxIdentity(DQuaternion q) {
        return Math.abs(Math.abs(q.w) - 1.0) < 1e-4
            && Math.abs(q.x) < 1e-4 && Math.abs(q.y) < 1e-4 && Math.abs(q.z) < 1e-4;
    }

    private static BlockPos blockAligned(Vec3 d) {
        long rx = Math.round(d.x), ry = Math.round(d.y), rz = Math.round(d.z);
        if (Math.abs(d.x - rx) > 0.01 || Math.abs(d.y - ry) > 0.01 || Math.abs(d.z - rz) > 0.01) {
            return null;
        }
        return new BlockPos((int) rx, (int) ry, (int) rz);
    }
}
