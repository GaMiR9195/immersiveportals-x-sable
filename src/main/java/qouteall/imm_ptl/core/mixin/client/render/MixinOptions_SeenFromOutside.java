package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;

/**
 * While the first-person camera entity is drawn as seen from outside (in a portal view, or its
 * projection beyond a portal), the camera counts as third person: model and animation mods that
 * hide parts of their own player in first person (EMF animation packs, first-person body mods)
 * draw the whole model there.
 */
@Mixin(Options.class)
public class MixinOptions_SeenFromOutside {
    @Inject(method = "getCameraType", at = @At("HEAD"), cancellable = true)
    private void ip_thirdPersonFromOutside(CallbackInfoReturnable<CameraType> cir) {
        if (CrossPortalEntityRenderer.isCameraEntitySeenFromOutside()) cir.setReturnValue(CameraType.THIRD_PERSON_BACK);
    }
}
