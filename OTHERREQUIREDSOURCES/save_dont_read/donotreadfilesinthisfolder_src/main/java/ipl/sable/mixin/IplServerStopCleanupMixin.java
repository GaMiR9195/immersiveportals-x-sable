package ipl.sable.mixin;

import ipl.sable.transit.SableTransitController;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears hosted transit state when the server stops. */
@Mixin(MinecraftServer.class)
public abstract class IplServerStopCleanupMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void ipl$cleanupOnStop(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        try {
            SableTransitController.onServerStopping(server);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("ipl-sable-transit")
                .warn("[IPL-TRANSIT] server-stop cleanup threw", t);
        }
    }
}
