package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.transit.IplAttachmentGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records constraints to the static world so finished crossings can wait for detachment. */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class IplConstraintTrackerMixin {

    @Inject(method = "addConstraint", at = @At("RETURN"), remap = false, require = 0)
    private void ipl$trackConstraint(PhysicsPipelineBody bodyA, PhysicsPipelineBody bodyB,
                                     PhysicsConstraintConfiguration<?> configuration,
                                     CallbackInfoReturnable<PhysicsConstraintHandle> cir) {
        try {
            IplAttachmentGate.onConstraintAdded(bodyA, bodyB, configuration, cir.getReturnValue());
        } catch (Throwable ignored) {
            // Bookkeeping must never break constraint creation.
        }
    }
}
