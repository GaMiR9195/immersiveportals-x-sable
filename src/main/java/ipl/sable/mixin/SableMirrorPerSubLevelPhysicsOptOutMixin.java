package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.mixin.iface.IplKinematicSubLevelHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip per-sub-level physics tick callbacks for kinematic mirrors.
 *
 * <p>{@code SubLevelPhysicsSystem.tickPipelinePhysics} iterates every server sub-level
 * in the container and calls these methods on each:
 * <ul>
 *   <li>{@code prePhysicsTickBegin()} — resets queued forces (harmless if skipped)</li>
 *   <li>{@code updateMergedMassData()} — updates mass tracker (harmless if skipped)</li>
 *   <li>{@code prePhysicsTick()} — computes lift/drag/etc forces; uses
 *       {@code SubLevelPhysicsSystem.getPhysicsHandle(this)} which returns null for
 *       bodies not in the pipeline → NPE</li>
 *   <li>{@code applyQueuedForces()} — same handle issue</li>
 * </ul>
 *
 * <p>We cancel the two that touch the physics handle (prePhysicsTick + applyQueuedForces)
 * for kinematic mirrors. The other two are no-ops in their effect on mirror behavior
 * but we don't bother cancelling them — they cost almost nothing and skipping them
 * could cause subtle state desync.
 */
@Pseudo
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableMirrorPerSubLevelPhysicsOptOutMixin {

    @Inject(method = "prePhysicsTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipPrePhysicsTick(Object physicsSystem, Object handle, double timeStep, CallbackInfo ci) {
        if (this instanceof IplKinematicSubLevelHolder holder && holder.ipl$isKinematicMirror()) {
            ci.cancel();
        }
    }

    @Inject(method = "applyQueuedForces", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipApplyQueuedForces(Object physicsSystem, Object handle, double timeStep, CallbackInfo ci) {
        if (this instanceof IplKinematicSubLevelHolder holder && holder.ipl$isKinematicMirror()) {
            ci.cancel();
        }
    }
}
