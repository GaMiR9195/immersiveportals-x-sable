package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import ipl.sable.client.IplSharedClock;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every client container uses the one shared interpolation clock ({@link IplSharedClock}).
 *
 * <p>The field itself is replaced, not just {@code getInterpolation()}: other mods read it
 * directly (Simulated's rope interpolation at the container tick tail). The container tick
 * advances the clock only on the first container tick of a client tick.
 */
@Mixin(value = ClientSubLevelContainer.class, remap = false)
public abstract class ClientSubLevelContainerMixin {

    @Shadow @Final @Mutable private ClientSableInterpolationState interpolation;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void ipl$useSharedClock(Level level, int logSideLength, int logPlotSize, int originX, int originZ,
                                    CallbackInfo ci) {
        this.interpolation = IplSharedClock.get();
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/client/ClientSableInterpolationState;tick()V"
        ),
        require = 1
    )
    private void ipl$tickSharedClockOnce(ClientSableInterpolationState clock) {
        IplSharedClock.tickOnce(clock);
    }
}
