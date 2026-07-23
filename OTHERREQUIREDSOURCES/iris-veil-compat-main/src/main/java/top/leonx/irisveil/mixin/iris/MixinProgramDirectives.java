package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.leonx.irisveil.accessors.ProgramDirectivesAccessor;

import java.util.Optional;

@Mixin(value = ProgramDirectives.class,remap = false)
public class MixinProgramDirectives implements ProgramDirectivesAccessor {
    @Unique
    private AlphaTest irisveilAlphaTestOverride;

    @Override
    public void irisveil$setAlphaTestOverride(AlphaTest alphaTest) {
        irisveilAlphaTestOverride = alphaTest;
    }

    @Inject(method = "getAlphaTestOverride", at = @At("HEAD"), cancellable = true)
    private void injectAlphaTestOverride(CallbackInfoReturnable<Optional<AlphaTest>> cir){
        if (irisveilAlphaTestOverride!=null)
            cir.setReturnValue(Optional.of(irisveilAlphaTestOverride));
    }
}
