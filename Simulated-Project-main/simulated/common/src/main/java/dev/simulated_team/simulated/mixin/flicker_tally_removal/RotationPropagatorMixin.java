package dev.simulated_team.simulated.mixin.flicker_tally_removal;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RotationPropagator.class, priority = 100000)
public class RotationPropagatorMixin {

    // TODO: REPLACE WITH MORE SOPHISTICATED FLICKER SYSTEM
    @WrapOperation(method = "propagateNewSource", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;getFlickerScore()I"))
    private static int removeFlicker(final KineticBlockEntity instance, final Operation<Integer> original) {
        return 0;
    }

}
