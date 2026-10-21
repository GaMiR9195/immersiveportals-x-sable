package ipl.sable.mixin.client;

import ipl.sable.render.IplProgramBindHook;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ExtendedShader overrides apply without calling ShaderInstance.apply. Its own
 * updateUniforms uploads the superclass Uniform list AFTER ProgramManager's bind.
 * Synchronize that list before it can overwrite either DSA-uploaded sub-level cut. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class IplIrisShaderClipMixin {

    @Inject(method = "updateUniforms", at = @At("HEAD"), remap = false)
    private void ipl$prepareClipUniforms(CallbackInfo ci) {
        IplProgramBindHook.prepareShader((ShaderInstance) (Object) this);
    }
}
