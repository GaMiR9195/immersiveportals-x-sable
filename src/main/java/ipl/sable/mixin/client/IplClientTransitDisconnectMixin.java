package ipl.sable.mixin.client;

import ipl.sable.client.IplParentDimSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/** Reset client-only state across world/server lifetimes. */
@Mixin(value = ClientWorldLoader.class, remap = false)
public abstract class IplClientTransitDisconnectMixin {
    @Inject(method = "cleanUp", at = @At("HEAD"), remap = false, require = 1)
    private static void ipl$clearTransit(CallbackInfo ci) {
        IplParentDimSync.clear();
        ipl.sable.client.IplClientPlotEntityTicking.clear();
        ipl.sable.client.IplStaffPortalBeamRenderer.clear();
        ipl.sable.client.IplClientSoundFrames.clear();
    }
}
