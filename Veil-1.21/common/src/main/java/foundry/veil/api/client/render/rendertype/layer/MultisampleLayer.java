package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

/**
 * @param enabled Whether the state is enabled or not
 * @see <a href="https://learnopengl.com/Advanced-OpenGL/Anti-Aliasing">Anti-Aliasing</a>
 * @since 3.1.0
 */
public record MultisampleLayer(boolean enabled) implements RenderTypeLayer {

    public static final MapCodec<MultisampleLayer> CODEC = Codec.BOOL
            .optionalFieldOf("enabled", true)
            .xmap(MultisampleLayer::new, MultisampleLayer::enabled);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        if (this.enabled) {
            builder.addLayer(VeilRenderBridge.multisampleState());
        }
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.MULTISAMPLE.get();
    }
}
