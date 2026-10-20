package foundry.veil.forge.mixin.compat.iris;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.ext.iris.IrisRenderingPipelineExtension;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(ShaderProgramImpl.Wrapper.class)
public class ShaderWrapperMixin {

    @Inject(method = "apply", at = @At("TAIL"))
    public void apply(CallbackInfo ci, @Share("drawn") LocalBooleanRef drawnRef) {
        Optional<WorldRenderingPipeline> pipelineOptional = Iris.getPipelineManager().getPipeline();
        if (pipelineOptional.isEmpty() || !(pipelineOptional.get() instanceof IrisRenderingPipelineExtension extension)) {
            return;
        }

        drawnRef.set(true);
        extension.veil$bindSimpleFramebuffer();
    }

    @Inject(method = "clear", at = @At("TAIL"))
    public void clear(CallbackInfo ci, @Share("drawn") LocalBooleanRef drawnRef) {
        if (drawnRef.get()) {
            AdvancedFbo.unbind();
        }
    }
}
