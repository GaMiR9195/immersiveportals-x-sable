package ipl.sable.mixin;

import dev.ryanhcode.sable.api.event.SablePostPhysicsTickEvent;
import dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.platform.SableEventPlatformImpl;
import ipl.sable.dim.IplProcessingPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every listener registered through Sable's physics-tick API is one unit of work of the
 * physics pass it runs in (see {@link IplProcessingPass#unit}): the frame that followed the
 * previous listener's ships does not leak into the next listener.
 */
@Mixin(value = SableEventPlatformImpl.class, remap = false)
public abstract class IplPhysicsListenerUnitMixin {

    @ModifyVariable(method = "onPhysicsTick", at = @At("HEAD"), argsOnly = true, require = 1, remap = false)
    private SablePrePhysicsTickEvent ipl$preListenerUnit(SablePrePhysicsTickEvent listener) {
        if (listener == null) return null;
        return (system, timeStep) -> IplProcessingPass.unit(() -> listener.prePhysicsTick(system, timeStep));
    }

    @ModifyVariable(method = "onPostPhysicsTick", at = @At("HEAD"), argsOnly = true, require = 1, remap = false)
    private SablePostPhysicsTickEvent ipl$postListenerUnit(SablePostPhysicsTickEvent listener) {
        if (listener == null) return null;
        return (system, timeStep) -> IplProcessingPass.unit(() -> listener.postPhysicsTick(system, timeStep));
    }
}
