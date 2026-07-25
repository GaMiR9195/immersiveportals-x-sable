package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.VeilRenderer;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.impl.client.render.shader.injection.ShaderInjectionManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public class ShaderGameRendererMixin {

    @ModifyExpressionValue(method = {"lambda$reloadShaders$56", "method_36512"}, at = @At(value = "INVOKE", target = "Lcom/mojang/datafixers/util/Pair;getFirst()Ljava/lang/Object;"))
    public Object getShader(Object original) {
        if (Veil.platform().hasErrors()) {
            return original;
        }

        final ShaderInstance oldInstance = (ShaderInstance) original;
        String name = oldInstance.getName();
        ResourceLocation targetName = ResourceLocation.tryParse(name);
        if (targetName == null) {
            Veil.LOGGER.warn("Couldn't parse shader name '{}' as resource location", name);
            return original;
        }

        VeilRenderer renderer = VeilRenderSystem.renderer();
        ShaderInjectionManager injectionManager = renderer.getShaderInjectionManager();

        ResourceLocation target = ResourceLocation.fromNamespaceAndPath(targetName.getNamespace(), "shaders/core/" + targetName.getPath());
        ResourceLocation replacementId = injectionManager.getReplacement(target);
        if (replacementId == null) {
            Veil.LOGGER.debug("No replacement found for {}", name);
            return original;
        }

        ShaderProgram shader = renderer.getShaderManager().getShader(replacementId);
        if (shader == null) {
            Veil.LOGGER.error("Failed to replace vanilla shader '{}': replacement '{}' not found", name, replacementId);
            return original;
        }

        ShaderInstance newInstance = VeilRenderBridge.toShaderInstance(shader);
        Veil.LOGGER.info("Replaced vanilla shader '{}' with '{}'", name, replacementId);
        oldInstance.close();
        return newInstance;
    }
}
