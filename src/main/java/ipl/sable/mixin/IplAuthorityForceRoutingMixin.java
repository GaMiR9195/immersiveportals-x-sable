package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.transit.IplStraddleCloneBody;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Route external force/impulse/velocity applications to the AUTHORITATIVE straddle
 * body (declarative-straddle phase 1).
 *
 * <p>Everything that pushes a ship — physics actors (propellers, wheel drives), the
 * Simulated staff's drag servo, scripted impulses — goes through these three pipeline
 * methods, targeting the real body. While a straddle session holds SOURCE authority
 * that's correct: the real body integrates and the clone inherits through the pin.
 * After the authority swap the real body is the pinned follower — thrust applied to it
 * would reach the ship only via the minority-weighted transfer channel, attenuating
 * engines several-fold exactly in the late-crossing window. These wraps redirect such
 * applications to the clone (vectors mapped through the portal isometry), where they
 * act at full strength on the body that actually integrates.
 *
 * <p>Our own servo calls the natives directly, not through these pipeline methods, so
 * there is no recursion. Non-session bodies and source-authority sessions fall through
 * to the original call untouched.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class IplAuthorityForceRoutingMixin {

    @WrapOperation(
        method = "applyImpulse",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;applyForce(JIDDDDDDZ)V"
        ),
        require = 0
    )
    private void ipl$routeApplyForce(
        long scene, int bodyId, double x, double y, double z,
        double fx, double fy, double fz, boolean wakeUp, Operation<Void> original
    ) {
        if (!IplStraddleCloneBody.redirectApplyForce(scene, bodyId, x, y, z, fx, fy, fz)) {
            original.call(scene, bodyId, x, y, z, fx, fy, fz, wakeUp);
        }
    }

    @WrapOperation(
        method = "applyLinearAndAngularImpulse",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;applyForceAndTorque(JIDDDDDDZ)V"
        ),
        require = 0
    )
    private void ipl$routeForceTorque(
        long scene, int bodyId, double fx, double fy, double fz,
        double tx, double ty, double tz, boolean wakeUp, Operation<Void> original
    ) {
        if (!IplStraddleCloneBody.redirectForceTorque(scene, bodyId, fx, fy, fz, tx, ty, tz)) {
            original.call(scene, bodyId, fx, fy, fz, tx, ty, tz, wakeUp);
        }
    }

    @WrapOperation(
        method = "addLinearAndAngularVelocity",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;addLinearAngularVelocities(JIDDDDDDZ)V"
        ),
        require = 0
    )
    private void ipl$routeAddVelocity(
        long scene, int bodyId, double lx, double ly, double lz,
        double ax, double ay, double az, boolean wakeUp, Operation<Void> original
    ) {
        if (!IplStraddleCloneBody.redirectAddVelocity(scene, bodyId, lx, ly, lz, ax, ay, az)) {
            original.call(scene, bodyId, lx, ly, lz, ax, ay, az, wakeUp);
        }
    }
}
