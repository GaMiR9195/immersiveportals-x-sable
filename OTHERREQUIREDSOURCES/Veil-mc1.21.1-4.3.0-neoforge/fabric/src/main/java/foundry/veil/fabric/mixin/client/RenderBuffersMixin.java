package foundry.veil.fabric.mixin.client;

import foundry.veil.Veil;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricVeilRegisterBlockLayersEvent;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(RenderBuffers.class)
public class RenderBuffersMixin {

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void init(CallbackInfo ci) {
        Set<RenderType> blockLayers = new HashSet<>();
        FabricVeilRegisterBlockLayersEvent.EVENT.invoker().onRegisterBlockLayers(renderType -> {
            if (Veil.platform().isDevelopmentEnvironment() && renderType.bufferSize() > RenderType.SMALL_BUFFER_SIZE) {
                Veil.LOGGER.warn("Block render layer '{}' uses a large buffer size: {}. If this is intended you can ignore this message", VeilRenderType.getName(renderType), renderType.bufferSize());
            }
            blockLayers.add(renderType);
        });
        FabricRenderTypeStageHandler.setBlockLayers(blockLayers);
    }
}
