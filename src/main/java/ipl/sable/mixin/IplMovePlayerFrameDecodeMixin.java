package ipl.sable.mixin;

import ipl.sable.transit.IplEntityFrames;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decodes a ship-local move packet in the frame the client encoded it in (see
 * {@link IplEntityFrames}). Anchored on the first read of the packet position, which is
 * after Sable's own decode (injected right after {@code ensureRunningOnSameThread}).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class IplMovePlayerFrameDecodeMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getX(D)D",
            ordinal = 0
        ),
        require = 1
    )
    private void ipl$decodeInAttachedFrame(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        IplEntityFrames.decodeIncomingPosition(this.player, (IplEntityFrames.MovePacketPosition) packet);
    }
}
