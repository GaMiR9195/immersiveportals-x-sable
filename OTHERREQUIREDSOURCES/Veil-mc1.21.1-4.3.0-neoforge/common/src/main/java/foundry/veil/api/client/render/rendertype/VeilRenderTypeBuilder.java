package foundry.veil.api.client.render.rendertype;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Extended render type builder that adds support for custom layers.
 */
public interface VeilRenderTypeBuilder {

    VeilRenderTypeBuilder textureState(RenderStateShard.EmptyTextureStateShard state);

    VeilRenderTypeBuilder shaderState(RenderStateShard.ShaderStateShard state);

    VeilRenderTypeBuilder transparencyState(RenderStateShard.TransparencyStateShard state);

    VeilRenderTypeBuilder depthTestState(RenderStateShard.DepthTestStateShard state);

    VeilRenderTypeBuilder cullState(RenderStateShard.CullStateShard state);

    VeilRenderTypeBuilder lightmapState(RenderStateShard.LightmapStateShard state);

    VeilRenderTypeBuilder overlayState(RenderStateShard.OverlayStateShard state);

    VeilRenderTypeBuilder layeringState(RenderStateShard.LayeringStateShard state);

    VeilRenderTypeBuilder outputState(RenderStateShard.OutputStateShard state);

    VeilRenderTypeBuilder texturingState(RenderStateShard.TexturingStateShard state);

    VeilRenderTypeBuilder writeMaskState(RenderStateShard.WriteMaskStateShard state);

    VeilRenderTypeBuilder lineState(RenderStateShard.LineStateShard state);

    VeilRenderTypeBuilder colorLogicState(RenderStateShard.ColorLogicStateShard state);

    VeilRenderTypeBuilder addLayer(RenderStateShard shard);

    default RenderType.CompositeState create(boolean affectsOutline) {
        return this.create(affectsOutline ? RenderType.OutlineProperty.AFFECTS_OUTLINE : RenderType.OutlineProperty.NONE);
    }

    RenderType.CompositeState create(RenderType.OutlineProperty outlineProperty);
}
