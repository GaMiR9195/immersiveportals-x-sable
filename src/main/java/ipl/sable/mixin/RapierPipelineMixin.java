package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.atlas.IplFusedStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rapier pipeline hook for the armed step of the fused world.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierPipelineMixin {

    /**
     * The fused driver runs every pipeline's {@code physicsTick} per substep (contraption poses,
     * wake-ups), but only the armed call steps the shared world: one native step per substep,
     * after every chart pushed its kinematic poses. Stock natives step every pipeline.
     */
    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;step(JD)V"),
        require = 1
    )
    private void ipl$armWorldStep(long sceneHandle, double timeStep, Operation<Void> original) {
        if (IplFusedStep.mayStepWorld()) {
            original.call(sceneHandle, timeStep);
        }
    }
}
