package foundry.veil.api.flare.data.effect;

import com.mojang.serialization.Codec;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.flare.EffectHost;
import foundry.veil.api.flare.model.BakedShell;
import foundry.veil.api.util.CodecUtil;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @since 2.5.0
 */
public record FlareEffectTemplate(FlareEffectLayer[] effectLayers, FlareEffectLayer[] activeEffectLayers) {

    public static final Codec<FlareEffectTemplate> CODEC = CodecUtil.singleOrList(FlareEffectLayer.CODEC)
            .fieldOf("layers")
            .xmap(FlareEffectTemplate::new, template -> List.of(template.effectLayers))
            .codec();

    public FlareEffectTemplate(FlareEffectLayer[] effectLayers) {
        this(effectLayers, getActiveLayers(List.of(effectLayers)));
    }

    public FlareEffectTemplate(Collection<FlareEffectLayer> effectLayers) {
        this(effectLayers.toArray(FlareEffectLayer[]::new), getActiveLayers(effectLayers));
    }

    private static FlareEffectLayer[] getActiveLayers(Collection<FlareEffectLayer> effectLayers) {
        ArrayList<FlareEffectLayer> enabledLayers = new ArrayList<>(effectLayers.size());

        for (FlareEffectLayer effectLayer : effectLayers) {
            if (!effectLayer.isDisabled()) {
                enabledLayers.add(effectLayer);
            }
        }

        return enabledLayers.toArray(FlareEffectLayer[]::new);
    }

    public void render(EffectHost host, MatrixStack matrixStack, float partialTick) {
        this.render(host, matrixStack, partialTick, null);
    }

    public void render(EffectHost host, MatrixStack matrixStack, float partialTick, @Nullable Map<ResourceLocation, BakedShell> shellOverrides) {
        for (FlareEffectLayer effectLayer : this.activeEffectLayers) {
            effectLayer.render(host, matrixStack, partialTick, shellOverrides);
        }
    }
}
