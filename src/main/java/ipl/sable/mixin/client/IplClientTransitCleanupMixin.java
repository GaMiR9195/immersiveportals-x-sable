package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A reallocated UUID must not inherit the old object's portal coordinate frame. */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class IplClientTransitCleanupMixin {
    @Inject(method = "onRemove", at = @At("HEAD"), remap = false, require = 1)
    private void ipl$forgetTransit(CallbackInfo ci) {
        ClientSubLevel sub = (ClientSubLevel) (Object) this;
        if (ipl.sable.dim.IplDimAgnostic.isHostingLevel(sub.getLevel())) {
            IplParentDimSync.forget(sub.getUniqueId());
        }
    }
}
