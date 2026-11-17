package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisNewWorldRenderingPipeline;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisForegroundMask;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisHistoryTargets;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Set;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IEIrisNewWorldRenderingPipeline {
    @Shadow private boolean isRenderingWorld;
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private CenterDepthSampler centerDepthSampler;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Shadow @Final private Set<GlImage> customImages;

    @Inject(
        method = "finalizeLevelRendering", at = @At("HEAD"), cancellable = true
    )
    private void onFinalizeLevelRendering(CallbackInfo ci) {
        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer) {
            if (PortalRendering.isRendering()) {
                ci.cancel();
            }
        }
    }

    @Inject(
        method = "beginTranslucents",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterDeferredCompositeRendering(CallbackInfo ci) {
        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer r) {
            r.onAfterIrisDeferredCompositeRendering();
        }
    }

    // After the resize check, before any pass reads the history buffers.
    @Inject(
        method = "beginLevelRendering",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/targets/RenderTargets;isFullClearRequired()Z"
        )
    )
    private void ip_onBeginView(CallbackInfo ci) {
        IrisHistoryTargets.onBeginLevelRendering((IrisRenderingPipeline) (Object) this);
    }

    @Inject(method = "beginHand", at = @At("RETURN"))
    private void ip_onHandDepthCaptured(CallbackInfo ci) {
        IrisForegroundMask.onNoHandDepthCaptured(renderTargets);
    }

    @Override
    public void ip_setIsRenderingWorld(boolean cond) {
        isRenderingWorld = cond;
    }

    @Override
    public RenderTargets ip_getRenderTargets() {
        return renderTargets;
    }

    @Override
    public CenterDepthSampler ip_getCenterDepthSampler() {
        return centerDepthSampler;
    }

    @Override
    public ShaderStorageBufferHolder ip_getStorageBuffers() {
        return shaderStorageBufferHolder;
    }

    @Override
    public Set<GlImage> ip_getCustomImages() {
        return customImages;
    }
}
