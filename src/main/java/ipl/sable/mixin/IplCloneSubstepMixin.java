package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.transit.IplStraddleCloneBody;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Drive the straddle clone bodies' velocity servo immediately before each scene's physics
 * substep (spec §2.4): the clone chases {@code portalIsometry × realPose} with the real
 * body's mapped velocities plus a position/orientation correction term, so far-side
 * contacts resolve against physical mass at the right place every step.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class IplCloneSubstepMixin {

    @Shadow(remap = false)
    @Final
    private ServerLevel level;

    @WrapOperation(
        method = "tickPipelinePhysics",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;physicsTick(D)V"
        ),
        require = 0
    )
    private void ipl$servoClonesBeforeStep(PhysicsPipeline pipeline, double dt, Operation<Void> original) {
        IplStraddleCloneBody.servoPreStep(this.level, dt);
        original.call(pipeline, dt);
    }
}
