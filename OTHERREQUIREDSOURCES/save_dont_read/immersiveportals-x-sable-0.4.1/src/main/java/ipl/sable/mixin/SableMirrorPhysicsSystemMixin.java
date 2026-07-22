package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import ipl.sable.transit.MirrorAllocationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep kinematic mirrors out of the physics pipeline entirely -- they are not
 * physics bodies.
 *
 * <p><b>Replaces the old {@code SableMirrorPhysicsOptOutMixin} approach.</b> That
 * version enrolled the mirror in the pipeline normally and then disabled
 * interactions one at a time (pose readback, force application, removal, etc.) --
 * a whack-a-mole that kept missing touchpoints (the latest being
 * velocity-prediction's {@code getLinearVelocity}, which hung the server in native
 * code). The structural fix is simpler: a mirror never gets a native rigid body
 * at all. Its pose is driven purely by the portal mapping; its mass tracker is
 * built directly (needed only so the block-copy cascade has something to write
 * to); and every stray pipeline call on it becomes a safe no-op via
 * {@link SableRapierPipelineOwnershipGuardMixin} (which ignores any body not in
 * the pipeline's {@code activeSubLevels} map).
 *
 * <p>Three interception points on {@link SubLevelPhysicsSystem}:
 * <ol>
 *   <li><b>{@code onSubLevelAdded}</b> -- skip {@code pipeline.add} for a mirror
 *       (so no native body is created and it never enters {@code activeSubLevels}),
 *       but call {@code buildMassTracker()} ourselves first, because that's the one
 *       useful thing {@code pipeline.add} would have done and the block-copy
 *       cascade in {@code MirrorOps.spawnMirror} dereferences the mass tracker.
 *       Detection uses {@link MirrorAllocationGuard} rather than the per-instance
 *       kinematic flag, because this observer fires <em>during</em>
 *       {@code allocateSubLevel}, before {@code spawnMirror} can set that flag.</li>
 *   <li><b>{@code onSubLevelRemoved}</b> -- skip {@code pipeline.remove} for a
 *       mirror (there is no native body to free; calling remove would hand a stale
 *       id to native code). The per-instance flag is set by this point, so we read
 *       it directly.</li>
 *   <li><b>{@code updatePose}</b> -- skip the per-substep "read pose from pipeline,
 *       write to logicalPose" step for a mirror, so the (nonexistent) pipeline body
 *       never clobbers the portal-mapped pose. Carried over from the old mixin.</li>
 * </ol>
 *
 * <p>Client rendering is unaffected: the {@code SubLevelTrackingSystem} observer is
 * separate from the physics system and still runs on add/remove, so the mirror is
 * tracked and rendered exactly as before.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SableMirrorPhysicsSystemMixin {

    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-mirror-physics");

    @Inject(method = "onSubLevelAdded", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipMirrorEnrollment(SubLevel subLevel, CallbackInfo ci) {
        if (!MirrorAllocationGuard.allocatingMirror) {
            return;
        }
        // Mirror being allocated: skip pipeline.add (no native body), but build the
        // mass tracker that the block-copy cascade will write into. Mirrors that
        // somehow aren't ServerSubLevel can't be enrolled anyway -- let the original
        // run and throw its own UnsupportedOperationException.
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            try {
                serverSubLevel.buildMassTracker();
            } catch (Throwable t) {
                IPL$LOG.warn("[IPL-MIRROR-PHYS] buildMassTracker failed for mirror {}",
                    serverSubLevel.getUniqueId(), t);
            }
            ci.cancel();
        }
    }

    // onSubLevelRemoved's target signature is (SubLevel, SubLevelRemovalReason);
    // the @Inject handler MUST mirror that full parameter list before the
    // CallbackInfo, or mixin apply fails at load with InvalidInjectionException
    // (descriptor validation is runtime, not compile-time -- javac can't catch it).
    @Inject(method = "onSubLevelRemoved", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$skipMirrorRemoval(SubLevel subLevel, SubLevelRemovalReason reason, CallbackInfo ci) {
        if (subLevel instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror()) {
            // No native body to free -- calling pipeline.remove would pass a stale
            // id into native code.
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
