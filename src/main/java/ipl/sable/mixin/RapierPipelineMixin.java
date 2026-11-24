package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.atlas.IplFusedStep;
import ipl.sable.transit.IplContraptionMaterial;
import ipl.sable.transit.IplLinks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rapier pipeline: constraint links for transit groups, the armed step of the fused world and the
 * native ids of kinematic contraptions.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierPipelineMixin {

    /** Every Sable constraint, from any mod: links bodies for the flip or anchors them to the world. */
    @Inject(method = "addConstraint", at = @At("RETURN"))
    private void ipl$linkConstraint(PhysicsPipelineBody bodyA, PhysicsPipelineBody bodyB,
                                    PhysicsConstraintConfiguration<?> configuration,
                                    CallbackInfoReturnable<PhysicsConstraintHandle> cir) {
        IplLinks.onConstraint(bodyA, bodyB, configuration, cir.getReturnValue());
    }

    /** The native id of a kinematic contraption: its contacts are clipped by the labels of its blocks. */
    @WrapOperation(
        method = "add(Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;)V",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;createKinematicContraption(JII[D)V")
    )
    private void ipl$trackContraption(long sceneHandle, int mountId, int id, double[] pose, Operation<Void> original,
                                      @Local(argsOnly = true) KinematicContraption contraption) {
        original.call(sceneHandle, mountId, id, pose);
        if (mountId >= 0) IplContraptionMaterial.onNativeAdded(contraption, sceneHandle, id);
    }

    @Inject(method = "remove(Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;)V", at = @At("HEAD"))
    private void ipl$forgetContraption(KinematicContraption contraption, CallbackInfo ci) {
        IplContraptionMaterial.onNativeRemoved(contraption);
    }

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
