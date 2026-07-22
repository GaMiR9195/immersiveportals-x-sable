package foundry.veil.api.flare.data.effect;

import com.mojang.serialization.Codec;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.flare.EffectHost;
import foundry.veil.api.flare.FlareEffectManager;
import foundry.veil.api.flare.model.BakedShell;
import foundry.veil.api.util.CodecUtil;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static foundry.veil.Veil.LOGGER;

/**
 * @since 2.5.0
 */
public record FlareSubModule(ResourceLocation[] templates) {

    public static final Codec<FlareSubModule> CODEC = CodecUtil.singleOrList(ResourceLocation.CODEC)
            .xmap(FlareSubModule::new, module -> List.of(module.templates));

    public FlareSubModule(Collection<ResourceLocation> templates) {
        this(templates.toArray(ResourceLocation[]::new));
    }

    public void render(EffectHost host, MatrixStack matrixStack, float partialTick) {
        this.render(host, matrixStack, partialTick, null);
    }

    public void render(EffectHost host, MatrixStack matrixStack, float partialTick, @Nullable Map<ResourceLocation, BakedShell> shellOverrides) {
        for (ResourceLocation templateLocation : this.templates) {
            FlareEffectTemplate template = FlareEffectManager.getTemplate(templateLocation);
            if (template == null) {
                LOGGER.error("Template {} could not be found!", templateLocation);
                continue;
            }
            template.render(host, matrixStack, partialTick, shellOverrides);
        }
    }
}
