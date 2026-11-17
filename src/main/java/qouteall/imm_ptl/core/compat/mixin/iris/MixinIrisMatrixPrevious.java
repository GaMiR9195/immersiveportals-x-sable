package qouteall.imm_ptl.core.compat.mixin.iris;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

import java.util.function.Supplier;

/** gbufferPrevious* / dhPrevious* hold the matrix the same view used in the last frame. */
@Mixin(targets = "net.irisshaders.iris.uniforms.MatrixUniforms$Previous", remap = false)
public class MixinIrisMatrixPrevious {
    @Shadow @Final private Supplier<Matrix4fc> parent;

    @Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("HEAD"), cancellable = true)
    private void ip_get(CallbackInfoReturnable<Matrix4fc> cir) {
        IrisViewContexts.Slot slot = IrisViewContexts.active().slot(this);
        if (slot.advance() || !(slot.state instanceof Matrix4f[])) {
            Matrix4f current = new Matrix4f(parent.get());
            Matrix4f previous = slot.state instanceof Matrix4f[] s ? s[0] : current;
            // [0]: this frame's matrix (next frame's previous), [1]: the value handed out now
            slot.state = new Matrix4f[]{current, previous};
        }
        cir.setReturnValue(new Matrix4f(((Matrix4f[]) slot.state)[1]));
    }
}
