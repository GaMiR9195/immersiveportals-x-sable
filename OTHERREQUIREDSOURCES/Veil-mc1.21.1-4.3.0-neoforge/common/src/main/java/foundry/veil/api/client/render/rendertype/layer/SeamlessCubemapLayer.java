package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

/**
 * @param enabled Whether the state is enabled or not
 * @see <a href="https://wikis.khronos.org/opengl/Cubemap_Texture#Seamless_cubemap">Cubemap Textures</a>
 * @since 3.1.0
 */
public record SeamlessCubemapLayer(boolean enabled) implements RenderTypeLayer {

    public static final MapCodec<SeamlessCubemapLayer> CODEC = Codec.BOOL
            .optionalFieldOf("enabled", true)
            .xmap(SeamlessCubemapLayer::new, SeamlessCubemapLayer::enabled);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        if (this.enabled) {
            builder.addLayer(VeilRenderBridge.seamlessCubeMapState());
        }
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.SEAMLESS_CUBEMAP.get();
    }
}
