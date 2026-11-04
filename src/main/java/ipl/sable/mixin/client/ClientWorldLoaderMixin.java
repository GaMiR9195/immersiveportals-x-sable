package ipl.sable.mixin.client;

import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplSharedClock;
import ipl.sable.client.IplStaffPortalBeamRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/** Reset client-only state across world/server lifetimes. */
@Mixin(value = ClientWorldLoader.class, remap = false)
public abstract class ClientWorldLoaderMixin {
    @Inject(method = "cleanUp", at = @At("HEAD"), require = 1)
    private static void ipl$clearTransit(CallbackInfo ci) {
        IplParentDimSync.clear();
        IplSharedClock.reset();
        IplStaffPortalBeamRenderer.clear();
    }
}
