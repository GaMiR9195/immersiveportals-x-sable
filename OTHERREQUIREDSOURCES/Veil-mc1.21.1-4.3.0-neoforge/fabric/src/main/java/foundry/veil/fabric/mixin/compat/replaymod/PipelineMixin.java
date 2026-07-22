package foundry.veil.fabric.mixin.compat.replaymod;

import com.replaymod.render.rendering.Pipeline;
import foundry.veil.api.client.render.VeilRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Pipeline.class)
public class PipelineMixin {

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Lcom/replaymod/render/rendering/FrameCapturer;process()Ljava/util/Map;", shift = At.Shift.BEFORE), remap = false)
    public void beginFrame(CallbackInfo ci) {
        VeilRenderSystem.beginFrame();
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Lcom/replaymod/render/rendering/FrameCapturer;process()Ljava/util/Map;", shift = At.Shift.AFTER), remap = false)
    public void endFrame(CallbackInfo ci) {
        VeilRenderSystem.endFrame();
    }
}
