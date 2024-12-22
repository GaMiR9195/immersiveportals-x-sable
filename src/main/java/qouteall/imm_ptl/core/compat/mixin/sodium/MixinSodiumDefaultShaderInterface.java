package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

@Pseudo
@Mixin(value = DefaultShaderInterface.class, remap = false)
public class MixinSodiumDefaultShaderInterface {
    @Unique
    private GlUniformFloat4v uIPClippingEquation;
    
    @Inject(
        method = "<init>",
        at = @At("RETURN"),
//        require = 0,
        remap = false
    )
    private void onInit(
        ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci
    ) {
        this.uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new);
    }

    @Inject(
        method = "setupState",
        at = @At("RETURN"),
        remap = false
    )
    private void onSetup(CallbackInfo ci) {
        if (uIPClippingEquation != null) {
            if (FrontClipping.isClippingEnabled) {
                double[] equation = FrontClipping.getActiveClipPlaneEquationAfterModelView();
                uIPClippingEquation.set(new float[]{
                    (float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]
                });
            }
            else {
                uIPClippingEquation.set(new float[]{0, 0, 0, 1});
            }
        }
    }
}
