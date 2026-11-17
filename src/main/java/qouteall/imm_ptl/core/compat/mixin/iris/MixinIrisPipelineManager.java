package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisHistoryTargets;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

@Mixin(value = PipelineManager.class, remap = false)
public class MixinIrisPipelineManager {
    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void ip_onDestroyPipeline(CallbackInfo ci) {
        IrisHistoryTargets.reset();
        IrisViewContexts.reset();
    }
}
