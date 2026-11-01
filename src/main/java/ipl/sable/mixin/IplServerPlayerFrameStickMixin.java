package ipl.sable.mixin;

import ipl.sable.transit.IplEntityFrames;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the server-side "player moves with the ship it stands on" step follow the frame the
 * player is attached to (see {@link IplEntityFrames#correctServerStick}). Sable applies the
 * raw step at {@code tick} HEAD; this runs on the first statement after it.
 */
@Mixin(ServerPlayer.class)
public abstract class IplServerPlayerFrameStickMixin {

    @Inject(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;tick()V"),
        require = 1
    )
    private void ipl$stickInAttachedFrame(CallbackInfo ci) {
        IplEntityFrames.correctServerStick((ServerPlayer) (Object) this);
    }
}
