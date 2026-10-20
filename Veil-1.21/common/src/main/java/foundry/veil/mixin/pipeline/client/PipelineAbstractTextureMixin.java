package foundry.veil.mixin.pipeline.client;

import foundry.veil.ext.AbstractTextureExtension;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractTexture.class)
public class PipelineAbstractTextureMixin implements AbstractTextureExtension {
}
