package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.client.IplClientSoundFrames;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tickable sounds keep the world frame they were started in (see
 * {@link IplClientSoundFrames}).
 */
@Mixin(SoundEngine.class)
public abstract class IplHostedTickableSoundFrameMixin {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"), require = 0)
    private void ipl$captureFrameOnPlay(SoundInstance sound, CallbackInfo ci) {
        IplClientSoundFrames.capture(sound);
    }

    @Inject(method = "queueTickingSound", at = @At("HEAD"), require = 0)
    private void ipl$captureFrameOnQueue(TickableSoundInstance sound, CallbackInfo ci) {
        IplClientSoundFrames.capture(sound);
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), require = 0)
    private void ipl$captureFrameOnDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        IplClientSoundFrames.capture(sound);
    }

    @WrapOperation(
        method = "tickNonPaused",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/sounds/TickableSoundInstance;tick()V"),
        require = 0
    )
    private void ipl$tickInOriginFrame(TickableSoundInstance sound, Operation<Void> original) {
        IplClientSoundFrames.runInFrame(sound, () -> original.call(sound));
    }

    @WrapOperation(
        method = "tickNonPaused",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/sounds/TickableSoundInstance;canPlaySound()Z"),
        require = 0
    )
    private boolean ipl$canPlayInOriginFrame(TickableSoundInstance sound, Operation<Boolean> original) {
        return IplClientSoundFrames.callInFrame(sound, () -> original.call(sound));
    }
}
