package foundry.veil.mixin.scheduler;

import foundry.veil.ext.MinecraftServerExtension;
import foundry.veil.impl.TickTaskSchedulerImpl;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin implements MinecraftServerExtension {

    @Unique
    private TickTaskSchedulerImpl veil$tickTaskScheduler;

    @Inject(method = "stopServer", at = @At("RETURN"))
    public void stopServer(CallbackInfo ci) {
        if (this.veil$tickTaskScheduler != null) {
            this.veil$tickTaskScheduler.shutdown();
        }
        this.veil$tickTaskScheduler = null;
    }

    @Override
    public @Nullable TickTaskSchedulerImpl veil$getScheduler() {
        return this.veil$tickTaskScheduler;
    }

    @Override
    public TickTaskSchedulerImpl veil$getOrCreateScheduler() {
        if (this.veil$tickTaskScheduler == null) {
            this.veil$tickTaskScheduler = new TickTaskSchedulerImpl();
        }
        return this.veil$tickTaskScheduler;
    }
}
