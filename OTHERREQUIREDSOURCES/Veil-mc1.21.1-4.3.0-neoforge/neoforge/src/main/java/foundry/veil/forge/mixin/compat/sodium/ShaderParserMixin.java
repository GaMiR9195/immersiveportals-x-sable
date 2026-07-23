package foundry.veil.forge.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.impl.client.render.shader.processor.SodiumShaderProcessor;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ShaderParser.class)
public class ShaderParserMixin {

    @ModifyReturnValue(method = "parseShader(Ljava/lang/String;Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;)Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderParser$ParsedShader;", at = @At("RETURN"))
    private static ShaderParser.ParsedShader modifySource(ShaderParser.ParsedShader original) {
        try {
            int activeBuffers = VeilRenderSystem.renderer().getDynamicBufferManger().getActiveBuffers();
            SodiumShaderProcessor.setup(Minecraft.getInstance().getResourceManager());
            String modified = SodiumShaderProcessor.modify(activeBuffers, original.src());
            return new ShaderParser.ParsedShader(modified, original.includeIds());
        } catch (Throwable t) {
            Veil.LOGGER.error("Failed to apply Veil shader modifiers to shader: {}", SodiumShaderProcessor.getActiveShaderName(), t);
            return original;
        } finally {
            SodiumShaderProcessor.free();
        }
    }
}
