package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevent the physics pipeline's per-substep pose readback from overwriting a
 * kinematic mirror's externally-driven {@code logicalPose}.
 *
 * <p><b>Approach taken (vs. the first attempt):</b> letting the pipeline enroll the
 * mirror normally during {@code onSubLevelAdded}. Sable's {@code pipeline.add} calls
 * {@code subLevel.buildMassTracker()} as its first action; the mass tracker is then
 * dereferenced by every {@code handleBlockChange} during block copy. Cancelling
 * enrollment broke that invariant. So we now enroll the mirror in physics like a
 * regular sub-level — wasted CPU on simulation we don't read, but no cascade
 * breakage. {@code MirrorOps.syncMirrorPose} calls {@code pipeline.teleport} each
 * tick to keep the pipeline body's pose pinned to our externally-set pose, so the
 * simulation never visibly drifts.
 *
 * <p>The only thing we DO cancel is {@code SubLevelPhysicsSystem.updatePose}, which
 * is the per-substep "read pose from pipeline, write to logicalPose" step. Without
 * this cancel, the pipeline's simulated pose would clobber our portal-mapped pose.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SableMirrorPhysicsOptOutMixin {

    @Inject(method = "updatePose", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipKinematicPoseReadback(ServerSubLevel serverSubLevel, CallbackInfo ci) {
        if (serverSubLevel instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror()) {
            ci.cancel();
        }
    }
}
