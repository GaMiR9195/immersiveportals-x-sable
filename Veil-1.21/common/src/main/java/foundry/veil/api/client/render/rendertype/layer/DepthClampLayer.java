package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

/**
 * @param enabled Whether the state is enabled or not
 * @see <a href="https://paroj.github.io/gltut/Positioning/Tut05%20Depth%20Clamping.html">Depth Clamping</a>
 * @since 3.1.0
 */
public record DepthClampLayer(boolean enabled) implements RenderTypeLayer {

    public static final MapCodec<DepthClampLayer> CODEC = Codec.BOOL
            .optionalFieldOf("enabled", true)
            .xmap(DepthClampLayer::new, DepthClampLayer::enabled);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        if (this.enabled) {
            builder.addLayer(VeilRenderBridge.depthClampState());
        }
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.DEPTH_CLAMP.get();
    }
}
