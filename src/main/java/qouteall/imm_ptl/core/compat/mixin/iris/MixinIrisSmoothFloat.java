package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.parsing.SmoothFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

/** smooth() of shaders.properties custom uniforms advances once per frame and per view. */
@Mixin(value = SmoothFloat.class, remap = false)
public class MixinIrisSmoothFloat implements IrisViewContexts.Temporal {
    @Shadow private float accumulator;
    @Shadow private boolean hasInitialValue;

    @Unique private final IrisViewContexts.Binding ip_binding = new IrisViewContexts.Binding();

    @Inject(method = "updateAndGet", at = @At("HEAD"), cancellable = true)
    private void ip_onUpdate(float value, float halfLifeUp, float halfLifeDown, CallbackInfoReturnable<Float> cir) {
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
