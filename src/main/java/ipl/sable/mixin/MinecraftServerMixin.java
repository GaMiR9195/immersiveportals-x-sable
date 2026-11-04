package ipl.sable.mixin;

import ipl.sable.transit.IplTransitController;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears transit state when the server stops — at {@code stopServer} RETURN, not HEAD: the final
 * world save runs inside {@code stopServer}, and the ship-portal anchor map must still be
 * populated when portal entities serialize (their persistence tag is derived from it). By then
 * nothing ticks these maps any more.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void ipl$cleanupOnStop(CallbackInfo ci) {
        IplTransitController.onServerStopping((MinecraftServer) (Object) this);
    }
}
