package foundry.veil.fabric.mixin.client;

import foundry.veil.fabric.FabricRenderTypeStageHandler;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = RenderType.class, priority = 500)
public class RenderTypeMixin {

    @Inject(method = "chunkBufferLayers", at = @At("RETURN"), cancellable = true)
    private static void injectChunkBufferLayers(CallbackInfoReturnable<List<RenderType>> cir) {
        List<RenderType> renderTypes = FabricRenderTypeStageHandler.getBlockLayers(cir.getReturnValue());
        if (renderTypes != null) {
            cir.setReturnValue(renderTypes);
        }
    }
}
