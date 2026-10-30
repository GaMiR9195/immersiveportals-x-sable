package ipl.sable.mixin.client;

import foundry.veil.api.network.handler.PacketContext;
import ipl.sable.client.IplParentDimSync;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cross-dimension move: the server's stop-tracking of the old body object arrives right
 * after the {@code move} RPC. Keep that client object as a transit ghost until its
 * successor in the destination level can be drawn (see {@link IplParentDimSync}).
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket", remap = false)
public abstract class IplGhostStopTrackingMixin {

    @Shadow public abstract long plotCoordinate();

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$deferGhostRemoval(PacketContext context, CallbackInfo ci) {
        Level level = context.level();
        if (level == null || !level.isClientSide()) return;
        long plot = this.plotCoordinate();
        if (IplParentDimSync.deferStopTracking(level, ChunkPos.getX(plot), ChunkPos.getZ(plot))) {
            ci.cancel();
        }
    }
}
