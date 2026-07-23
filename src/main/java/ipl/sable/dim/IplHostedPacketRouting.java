package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Per-packet dimension resolution for the dim-agnostic hosting model.
 *
 * <p><b>The origin rule:</b> the client keeps STOCK Sable semantics — a sub-level's
 * plot chunks, block entities and tracking state live in the {@code ClientLevel} of the
 * dimension the ship visually occupies (its parent), which for a same-dimension player is
 * exactly {@code Minecraft.level}. The server hosts plots in {@code ipl_sable:sublevels},
 * so every packet describing hosted content must be stamped with the owning ship's PARENT
 * dimension, never with the hosting dimension. Nothing on the client then needs to know
 * the hosting dimension exists — third-party mods (Simulated ropes/springs, Offroad
 * wheels, Aeronautics block entities) see the vanilla-Sable world again.
 *
 * <p>Wired into {@link qouteall.imm_ptl.core.network.PacketRedirection#createRedirectedMessage}:
 * whenever a packet is about to be stamped with the hosting dimension (the tick-wide
 * force-redirect of the hosting level, ChunkHolder broadcasts of plot chunks, tracked-entity
 * sync of plot-resident entities), this resolver rewrites the stamp per packet:
 *
 * <ul>
 *   <li>vanilla chunk/light/block/block-entity packets → plot chunk coords → owning ship
 *       → parent dimension;</li>
 *   <li>Sable tracking payloads (start/stop/bounds/finalize/stop-moving) → container-local
 *       plot slot → owning ship → parent dimension;</li>
 *   <li>UUID-keyed / batched / player-global payloads (movement snapshot batches,
 *       interpolation info) → {@code null} = send UNSTAMPED. They are applied per
 *       sub-level id on the client, where {@link
 *       ipl.sable.mixin.client.IplClientSubLevelLookupBridgeMixin} resolves the id across
 *       client levels.</li>
 * </ul>
 *
 * <p>Stamps for non-hosting dimensions pass through untouched (legacy per-level Sable
 * behavior, IP's own redirection).
 */
public final class IplHostedPacketRouting {

    private IplHostedPacketRouting() {}

    /**
     * Remap {@code requested} for {@code packet}. Returns the dimension to stamp, or
     * {@code null} for "do not stamp this packet at all".
     */
    @Nullable
    public static ResourceKey<Level> remapRedirectDimension(
        MinecraftServer server, ResourceKey<Level> requested, Packet<?> packet
    ) {
        if (requested != SableSubLevelDimension.SUBLEVELS) {
            return requested; // not hosting-dim content — untouched
        }
        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(server);
        if (hosting == null) {
            return requested;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(hosting);
        if (container == null) {
            return requested;
        }

        // --- vanilla packets carrying plot coordinates -------------------------------
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            return parentByPlotChunk(container, chunk.getX(), chunk.getZ(), requested);
        }
        if (packet instanceof ClientboundLightUpdatePacket light) {
            return parentByPlotChunk(container, light.getX(), light.getZ(), requested);
        }
        if (packet instanceof ClientboundBlockUpdatePacket block) {
            return parentByPlotBlock(container, block.getPos(), requested);
        }
        if (packet instanceof ClientboundBlockEntityDataPacket beData) {
            return parentByPlotBlock(container, beData.getPos(), requested);
        }
        if (packet instanceof ClientboundBlockEventPacket blockEvent) {
            return parentByPlotBlock(container, blockEvent.getPos(), requested);
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            BlockPos[] first = new BlockPos[1];
            sectionUpdate.runUpdates((pos, state) -> {
                if (first[0] == null) first[0] = pos.immutable();
            });
            if (first[0] != null) {
                return parentByPlotBlock(container, first[0], requested);
            }
            return requested;
        }
        if (packet instanceof ClientboundAddEntityPacket addEntity) {
            return parentByPlotBlock(container,
                BlockPos.containing(addEntity.getX(), addEntity.getY(), addEntity.getZ()), requested);
        }

        // --- Sable custom payloads ----------------------------------------------------
        if (packet instanceof ClientboundCustomPayloadPacket customPayload) {
            return remapSablePayload(container, customPayload.payload(), requested);
        }

        // Anything else emitted under the hosting redirect (entity data/movement by id,
        // sounds, ...) has no per-packet frame; send unstamped so it lands in the
        // recipient's current level, where id-based client lookups bridge across levels.
        return null;
    }

    @Nullable
    private static ResourceKey<Level> remapSablePayload(
        SubLevelContainer container, CustomPacketPayload payload, ResourceKey<Level> requested
    ) {
        // Container-LOCAL plot slot coordinates (SubLevelTrackingSystem.getSubLevelLong).
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket p) {
            return parentByLocalSlot(container, p.plotCoordinate(), requested);
        }
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket p) {
            return parentByLocalSlot(container, p.plotCoordinate(), requested);
        }
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeBoundsSubLevelPacket p) {
            return parentByLocalSlot(container, p.plotCoordinate(), requested);
        }
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket p) {
            return parentByLocalSlot(container, p.plotCoordinate(), requested);
        }
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundStopMovingSubLevelPacket p) {
            return parentByLocalSlot(container, p.plotCoordinate(), requested);
        }
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeSubLevelNamePacket p) {
            SubLevel sub = container.getSubLevel(p.subLevelID());
            return parentOf(sub, requested);
        }
        if (payload instanceof dev.ryanhcode.sable.network.packets.tcp.ClientboundRecentlySplitSubLevelPacket p) {
            SubLevel sub = container.getSubLevel(p.splitSubLevelID());
            return parentOf(sub, requested);
        }
        // Movement snapshot batches can span ships with different parents, and the
        // interpolation-info payload is per player. Unstamped; per-id client routing.
        if (payload instanceof dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket
            || payload instanceof dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket) {
            return null;
        }
        // IP's own redirect payload never reaches here (isRedirectPacket short-circuits).
        // Unknown payloads under the hosting redirect: unstamped (recipient's level).
        return null;
    }

    private static ResourceKey<Level> parentByPlotChunk(
        SubLevelContainer container, int chunkX, int chunkZ, ResourceKey<Level> fallback
    ) {
        LevelPlot plot = container.getPlot(new ChunkPos(chunkX, chunkZ));
        return plot == null ? fallback : parentOf(plot.getSubLevel(), fallback);
    }

    private static ResourceKey<Level> parentByPlotBlock(
        SubLevelContainer container, BlockPos pos, ResourceKey<Level> fallback
    ) {
        return parentByPlotChunk(container, pos.getX() >> 4, pos.getZ() >> 4, fallback);
    }

    private static ResourceKey<Level> parentByLocalSlot(
        SubLevelContainer container, long localPlotCoordinate, ResourceKey<Level> fallback
    ) {
        SubLevel sub = container.getSubLevel(
            ChunkPos.getX(localPlotCoordinate), ChunkPos.getZ(localPlotCoordinate));
        return parentOf(sub, fallback);
    }

    private static ResourceKey<Level> parentOf(@Nullable SubLevel sub, ResourceKey<Level> fallback) {
        if (sub == null) {
            return fallback;
        }
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
        // Parent unresolved (freshly deserialized, pre-restore): keep the hosting stamp —
        // nothing tracks such a ship yet, and the legacy behavior is the safe default.
        return parent == null ? fallback : parent.dimension();
    }
}
