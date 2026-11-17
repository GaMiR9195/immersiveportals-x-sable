package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

@Mixin(value = ProgramUniforms.class, remap = false)
public class MixinIrisProgramUniforms {
    // Per-frame uniforms are uploaded once per view, not once per frame.
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/uniforms/SystemTimeUniforms$FrameCounter;getAsInt()I"
        )
    )
    private int ip_perViewEpoch(SystemTimeUniforms.FrameCounter counter) {
        return IrisViewContexts.uniformEpoch();
    }
}
