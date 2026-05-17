package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import ipl.sable.transit.IplMirrorIncomingTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt kinematic-mirror sub-levels out of Sable's physics pipeline + pose readback.
 *
 * <p>Phase 2 spawns a mirror sub-level in the destination dim when an airship approaches
 * a portal. The mirror should mirror the source's pose, not be simulated independently
 * by Sable's physics engine. This mixin:
 *
 * <ul>
 *   <li>Cancels {@code pipeline.add()} in {@code onSubLevelAdded} for mirror UUIDs
 *       (identified via the {@link IplMirrorIncomingTracker} thread-local because the
 *       observer fires synchronously inside {@code allocateSubLevel}, before we can
 *       set the kinematic flag on the resulting sub-level instance).</li>
 *   <li>Cancels {@code updatePose} (which reads pose from the physics pipeline and
 *       writes it back to {@code logicalPose}) for sub-levels flagged as kinematic.</li>
 * </ul>
 *
 * <p>Why both: the incoming-UUID check handles the immediate post-allocation observer
 * fire. The flag check handles all subsequent ticks where the kinematic flag has been
 * set on the sub-level instance.
 *
 * <p>We also set the kinematic flag on the sub-level during the {@code onSubLevelAdded}
 * call so subsequent reads see the flag without needing the thread-local.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SableMirrorPhysicsOptOutMixin {

    @Inject(method = "onSubLevelAdded", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipKinematicEnrollment(SubLevel subLevel, CallbackInfo ci) {
        boolean isIncoming = IplMirrorIncomingTracker.isIncoming(subLevel.getUniqueId());
        boolean isFlagged = subLevel instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror();

        if (isIncoming || isFlagged) {
            // Eagerly propagate the flag onto the sub-level instance for downstream
            // checks (updatePose, prePhysicsTick, applyQueuedForces). This makes the
            // thread-local only matter for the very first event in the allocation
            // path; everything afterward consults the flag.
            if (subLevel instanceof IplKinematicSubLevelHolder holder) {
                holder.ipl$setKinematicMirror(true);
            }
            ci.cancel();
        }
    }

    @Inject(method = "updatePose", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipKinematicPoseReadback(ServerSubLevel serverSubLevel, CallbackInfo ci) {
        if (serverSubLevel instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror()) {
            ci.cancel();
        }
    }
}
