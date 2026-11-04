package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import foundry.veil.api.network.handler.PacketContext;
import ipl.sable.client.IplParentDimSync;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Makes Sable's {@code ClientboundStartTrackingSubLevelPacket.handle} idempotent per client level.
 *
 * <p>IP keeps the client levels of other dimensions alive for portal views, so a client can
 * receive StartTracking for a sub-level it still tracks (it left the range and came back, or
 * crossed a portal and returned). Upstream {@code allocateSubLevel} then throws
 * {@code "Plot already exists at x, z"} and the client disconnects. Two cases:
 * <ol>
 *   <li><b>Same UUID already tracked</b>: the re-emit carries no new state (poses and bounds
 *       arrive on their own packets), so the allocation is skipped.</li>
 *   <li><b>Another UUID on the plot</b>: the server cannot place two sub-levels on one plot,
 *       so the occupant is stale (its StopTracking was missed) and is evicted.</li>
 * </ol>
 *
 * <p>Once Sable has created the object, the successor of a cross-dimension move continues its
 * transit ghost: its poses and snapshot history ({@link IplParentDimSync#continueFromGhost}).
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket", remap = false)
public abstract class StartTrackingPacketMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable");

    /**
     * Record accessors. Sable's packet is a {@code record}; @Shadow binds to the
     * synthetic component accessors by name.
     */
    @Shadow public abstract UUID subLevelID();

    @Shadow public abstract long plotCoordinate();

    @Inject(
        method = "handle",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$dedupeStartTracking(PacketContext context, CallbackInfo ci) {
        Level level = context.level();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        UUID uuid = this.subLevelID();
        // A transit ghost of this body still held in this level yields to the new object.
        IplParentDimSync.onStartTracking(level, uuid);

        if (container.getSubLevel(uuid) != null) {
            LOG.debug("[IPL-SABLE] Skipping duplicate StartTracking for sub-level {} (already tracked)", uuid);
            ci.cancel();
            return;
        }

        // Plot coordinates are decoded the way the handler does (ChunkPos.getX/getZ of the packed long).
        long plot = this.plotCoordinate();
        int x = ChunkPos.getX(plot);
        int z = ChunkPos.getZ(plot);
        SubLevel occupant = container.getSubLevel(x, z);
        if (occupant != null && !occupant.getUniqueId().equals(uuid)) {
            LOG.warn("[IPL-SABLE] StartTracking plot collision at ({}, {}): evicting stale "
                    + "occupant {} for incoming {}", x, z, occupant.getUniqueId(), uuid);
            try {
                container.removeSubLevel(x, z, SubLevelRemovalReason.REMOVED);
            } catch (RuntimeException e) {
                // The plot stays occupied: allocating would throw. The incoming sub-level stays
                // untracked until the next emit.
                LOG.error("[IPL-SABLE] failed to evict stale occupant at ({}, {}); "
                        + "skipping StartTracking for {}", x, z, uuid, e);
                ci.cancel();
            }
        }
    }

    @Inject(method = "handle", at = @At("RETURN"))
    private void ipl$continueFromGhost(PacketContext context, CallbackInfo ci) {
        IplParentDimSync.continueFromGhost(context.level(), this.subLevelID());
    }
}
