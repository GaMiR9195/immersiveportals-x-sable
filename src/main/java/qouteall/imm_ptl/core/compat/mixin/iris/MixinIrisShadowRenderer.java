package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalSurface;

@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinIrisShadowRenderer {
    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;copyPreTranslucentDepth(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;)V"
        )
    )
    private void ip_beforeOpaqueDepthCopy(LevelRendererAccessor levelRenderer, Camera playerCamera, CallbackInfo ci) {
        IrisPortalSurface.renderShadowCasters(playerCamera, false);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;restorePlayerProjection()V"
        )
    )
    private void ip_afterTranslucentTerrain(LevelRendererAccessor levelRenderer, Camera playerCamera, CallbackInfo ci) {
        IrisPortalSurface.renderShadowCasters(playerCamera, true);
    }
}
