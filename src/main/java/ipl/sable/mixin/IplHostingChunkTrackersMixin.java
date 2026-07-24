package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Vanilla chunk-tracking player lookups on PLOT chunks — asked of ANY level — resolve to
 * the owning sub-level's TRACKING PLAYERS.
 *
 * <p>Mods address per-chunk packet sinks through {@code ChunkMap.getPlayers} (NeoForge
 * {@code PacketDistributor.trackingChunk}, Veil's {@code VeilPacketManager.tracking(be)},
 * Create's chunk-targeted syncs, Simulated's rope strand snapshots via
 * {@code chunkMap.getPlayers(chunkOf(holderPos))}). A plot chunk has NO vanilla trackers
 * in any dimension — players never watch plot-grid chunk positions — so every such sink
 * sent to nobody: rope strands never received their point snapshots (fully invisible
 * ropes), spring/BE live state silently dropped. Stock Sable never hit this because its
 * tracking helper doubled as vanilla tracking in the player's own dimension.
 *
 * <p>Generalized beyond the hosting level: with the server identity fix
 * ({@code IplHostedBeParentIdentityMixin}), hosted plot BEs resolve their PARENT level and
 * ask the parent's chunk map about their plot-range chunk — the answer must be the same.
 * The plot grid is a universal address space; the hosting container knows the trackers.
 *
 * <p>Same doctrine as {@code IplPlotDeferredLogicMixin}'s block-event broadcast fix, one
 * layer lower so EVERY tracking-based sink inherits it.
 */
@Mixin(ChunkMap.class)
public abstract class IplHostingChunkTrackersMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @ModifyReturnValue(method = "getPlayers", at = @At("RETURN"))
    private List<ServerPlayer> ipl$plotChunkTrackers(List<ServerPlayer> original, ChunkPos pos, boolean boundaryOnly) {
        if (!original.isEmpty()) return original;
        if (Math.abs(pos.x) < 62_500 && Math.abs(pos.z) < 62_500) return original;

        SubLevelContainer container = IplDimAgnostic.isHostingLevel(this.level)
            ? SubLevelContainer.getContainer((Level) this.level)
            : IplDimAgnostic.getHostingContainerFor(this.level);
        if (container == null) return original;
        List<ServerPlayer> tracking = container.getPlayersTracking(pos);
        return tracking == null || tracking.isEmpty() ? original : tracking;
    }
}
