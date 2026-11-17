package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

/** Built-in smoothed uniforms advance once per frame and per view. */
@Mixin(value = SmoothedFloat.class, remap = false)
public class MixinIrisSmoothedFloat implements IrisViewContexts.Temporal {
    @Shadow private float accumulator;
    @Shadow private boolean hasInitialValue;

    @Unique private final IrisViewContexts.Binding ip_binding = new IrisViewContexts.Binding();

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void ip_onUpdate(CallbackInfo ci) {
        if (!ip_binding.bindAndAdvance(this)) ci.cancel();
    }

    @Inject(method = "getAsFloat", at = @At("HEAD"))
    private void ip_onRead(CallbackInfoReturnable<Float> cir) {
        ip_binding.bind(this);
    }

    @Override
    public Object ip_captureTemporal() {
        return hasInitialValue ? accumulator : null;
    }

    @Override
    public void ip_restoreTemporal(Object state) {
        hasInitialValue = state != null;
        accumulator = state instanceof Float value ? value : 0;
    }
}
