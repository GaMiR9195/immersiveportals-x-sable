package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.parsing.SmoothFloat;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

/**
 * Smoothed values advance once per frame and per view: built-in smoothed uniforms
 * ({@link SmoothedFloat}) and smooth() of shaders.properties custom uniforms ({@link SmoothFloat}).
 */
@Mixin(value = {SmoothedFloat.class, SmoothFloat.class}, remap = false)
public class MixinIrisSmoothing implements IrisViewContexts.Temporal {
    @Shadow private float accumulator;
    @Shadow private boolean hasInitialValue;
    @Unique private final IrisViewContexts.Binding ip_binding = new IrisViewContexts.Binding();

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_onUpdate(CallbackInfo ci) {
        if (!ip_binding.bindAndAdvance(this)) ci.cancel();
    }

    @Inject(method = "getAsFloat", at = @At("HEAD"), require = 0)
    private void ip_onRead(CallbackInfoReturnable<Float> cir) {
        ip_binding.bind(this);
    }

    @Inject(method = "updateAndGet", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_onUpdateAndGet(float value, float halfLifeUp, float halfLifeDown, CallbackInfoReturnable<Float> cir) {
        if (!ip_binding.bindAndAdvance(this)) {
            cir.setReturnValue(hasInitialValue ? accumulator : value);
        }
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
