package dev.simulated_team.simulated.mixin.hold_interaction;

import com.llamalad7.mixinextras.sugar.Local;
import dev.simulated_team.simulated.events.SimulatedCommonClientEvents;
import dev.simulated_team.simulated.util.SimDistUtil;
import dev.simulated_team.simulated.util.click_interactions.InteractCallback;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "turnPlayer", cancellable = true,
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE, target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void simulated$turnPlayer(final double d, final CallbackInfo ci,
                                      @Local(ordinal = 4) final double j, @Local(ordinal = 5) final double k,
                                      @Local(ordinal = 0) final int l) {
        if (SimDistUtil.getClientPlayer() != null && !SimDistUtil.getClientPlayer().isSpectator()) {
            final InteractCallback.Result status = SimulatedCommonClientEvents.onMouseMove(j, k * l);
            if (status.cancelled()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "onPress",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getOverlay()Lnet/minecraft/client/gui/screens/Overlay;", ordinal = 0),
            cancellable = true)
    private void simulated$preOnPress(final long windowPointer, final int button, final int action, final int modifiers, final CallbackInfo ci, @Local(ordinal = 1, argsOnly = true) final int i, @Local(argsOnly = true, ordinal = 0) final long l) {
        if (SimDistUtil.getClientPlayer() != null && !SimDistUtil.getClientPlayer().isSpectator()) {
            final InteractCallback.Result status = SimulatedCommonClientEvents.onBeforeMouseInput(InteractCallback.Input.mouse(button), modifiers, action);
            if (status.cancelled()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "onScroll",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getOverlay()Lnet/minecraft/client/gui/screens/Overlay;", ordinal = 0),
            cancellable = true)
    private void simulated$preOnScroll(final long l, final double d, final double e, final CallbackInfo ci, @Local(ordinal = 3) final double deltaX, @Local(ordinal = 4) final double deltaY) {
        if (SimDistUtil.getClientPlayer() != null && !SimDistUtil.getClientPlayer().isSpectator()) {
            final InteractCallback.Result status = SimulatedCommonClientEvents.onMouseScroll(deltaX, deltaY);
            if (status.cancelled()) {
                ci.cancel();
            }
        }
    }
}