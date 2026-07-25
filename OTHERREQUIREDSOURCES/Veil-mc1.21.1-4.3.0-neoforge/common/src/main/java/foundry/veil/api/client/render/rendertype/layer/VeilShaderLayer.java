package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import net.minecraft.resources.ResourceLocation;

public record VeilShaderLayer(LayerTemplateValue<ResourceLocation> shaderId) implements RenderTypeLayer {

    public static final MapCodec<VeilShaderLayer> CODEC = LayerTemplateValue.LOCATION_CODEC
            .fieldOf("name")
            .xmap(VeilShaderLayer::new, VeilShaderLayer::shaderId);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.shaderState(VeilRenderBridge.shaderState(this.shaderId.parse(params)));
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.VEIL_SHADER.get();
    }
}
