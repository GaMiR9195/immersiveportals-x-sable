package ipl.sable.mixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make the Rapier physics pipeline ignore any body it doesn't own.
 *
 * <p><b>The bug class this kills:</b> every per-body native call in
 * {@code RapierPhysicsPipeline} resolves the body's id via
 * {@code Rapier3D.getID(body)} -- which just returns {@code body.getRuntimeId()},
 * a value assigned at sub-level construction that is <em>always non-null</em> and
 * says nothing about whether a native Rapier rigid body actually exists. So if a
 * native call is made on a sub-level that was never enrolled (a kinematic mirror)
 * or whose body was already freed (a leaked/double-removed body), the call walks
 * into the native engine with a stale id and <em>hangs the server thread inside
 * native code with no exception</em>. The 31May watchdog dump caught exactly this:
 * {@code Rapier3D.getLinearVelocity (Native Method)} stuck for 18s+, reached via
 * {@code PhysicsChunkTicketManager.update}'s velocity-prediction path.
 *
 * <p><b>The invariant we enforce:</b> the pipeline keeps a map,
 * {@code activeSubLevels}, of exactly the bodies it created native rigid bodies
 * for (populated in {@code add}, cleared in {@code remove}). A per-body method
 * called on a body NOT in that map is operating on a body the pipeline doesn't
 * own -- so we no-op it. Getters return a zeroed/unchanged result; mutators
 * (force/velocity/teleport/wake/stats) return without touching native.
 *
 * <p>Keyed on native ownership, not on our mirror flag, so it is both the
 * structural enabler for "kinematic mirrors are not physics bodies" (mirrors are
 * never enrolled -- see {@code SableMirrorPhysicsSystemMixin} -- so every stray
 * pipeline call on them becomes a safe no-op) and a general Sable robustness fix
 * (any freed/leaked body is now inert instead of a native hang). Normal enrolled
 * airships are unaffected: their id IS in {@code activeSubLevels}, so the guard
 * passes through to the original every time.
 *
 * <p><b>Two failure modes, both covered.</b> The per-body getters/mutators
 * (velocity, pose, impulse, wake, stats) on an unowned body merely <em>hang</em>
 * or return garbage -- recoverable, no-op'd above. But the two STRUCTURAL
 * mutations -- {@code addConstraint} (the physics staff pinning a sub-level) and
 * {@code add(KinematicContraption)} (a Create contraption on a sub-level) -- do an
 * unchecked rigid-body lookup inside Rapier and <em>panic across the JNI boundary
 * with a hard process abort and no Java stack trace</em> when the body doesn't
 * exist. That's the staff-on-a-mirror crash. Because a mirror is a full block-copy
 * of its source, anything force/constraint-bearing the source has (propellers,
 * balloons, reaction wheels, Create contraptions, or a player's staff grab) will
 * try to enter Rapier on the mirror too. So the guard must cover the structural
 * mutations as well, not just the per-tick force path -- otherwise a mirror of a
 * powered airship aborts the game. Both structural methods already return
 * {@code null}/no-op for their own degenerate inputs, so an early mirror return is
 * consistent with their existing contract (callers null-check the handle).
 *
 * <p><b>Injector signature discipline:</b> {@code @Inject(at = HEAD)} handlers
 * must list the target method's parameters <em>in full</em> (or omit them
 * entirely) before the {@code CallbackInfo}; a partial list fails at apply time.
 * Each handler below mirrors the exact target signature in
 * {@code RapierPhysicsPipeline}. Targets that don't exist there (e.g. there is no
 * {@code getCenterOfMass} -- centre of mass comes from the mass tracker -- and
 * the force method is {@code applyImpulse}, not {@code applyForce}) are simply not
 * injected. {@code require = 0} is best-effort only; correctness here comes from
 * matching real methods with real signatures.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class SableRapierPipelineOwnershipGuardMixin {

    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-rapier-guard");

    @Shadow @Final private Int2ObjectMap<ServerSubLevel> activeSubLevels;

    @Shadow @Final private ServerLevel level;

    /** True when the pipeline has no native rigid body for this sub-level. */
    private boolean ipl$notOwned(PhysicsPipelineBody body) {
        return body == null || !this.activeSubLevels.containsKey(body.getRuntimeId());
    }

    private static long ipl$lastConstraintLogMs = 0;

    /** TEMPORARY bring-up diagnostic: prove constraint forwarding fires (rate-limited). */
    private void ipl$logConstraintForward(
        @Nullable ServerSubLevel a, @Nullable ServerSubLevel b, Object configuration
    ) {
        long now = System.currentTimeMillis();
        if (now - ipl$lastConstraintLogMs < 2000) return;
        ipl$lastConstraintLogMs = now;
        IPL$LOG.info("[IPL-RAPIER-GUARD] forwarded addConstraint a={} b={} config={}",
            a == null ? "world" : a.getUniqueId(),
            b == null ? "world" : b.getUniqueId(),
            configuration.getClass().getSimpleName());
    }

    /**
     * Dim-agnostic location transparency: a per-body call landing on the WRONG pipeline
     * (e.g. the physics staff resolving the player's dim's pipeline while the grabbed
     * sub-level's body lives in {@code ipl_sable:sublevels}'s pipeline) is forwarded to
     * the body's OWNING pipeline instead of being dropped. Returns null when there is no
     * forwarding target (then the original no-op guard applies — e.g. true mirrors).
     * No recursion: the owning pipeline's level == the body's level, so its own guard
     * never forwards again.
     */
    private RapierPhysicsPipeline ipl$forwardTarget(PhysicsPipelineBody body) {
        if (!ipl.sable.dim.IplDimAgnostic.isEnabled()) return null;
        if (!(body instanceof ServerSubLevel sub)) return null;
        if (!ipl.sable.dim.IplDimAgnostic.isHosted(sub)) return null;
        if (sub.getLevel() == this.level) return null; // we ARE the owning pipeline's level
        dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer owning =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(sub.getLevel());
        if (owning == null) return null;
        return owning.physicsSystem().getPipeline() instanceof RapierPhysicsPipeline rapier
            ? rapier : null;
    }

    /**
     * True when {@code sub} is non-null and the pipeline has no native rigid body
     * for it. Null subs are NOT "not owned" -- a null sub-level in a constraint
     * means "the static world" (Rapier id -1), which is always valid.
     */
    private boolean ipl$notOwnedSub(@Nullable ServerSubLevel sub) {
        return sub != null && !this.activeSubLevels.containsKey(sub.getRuntimeId());
    }

    // ---- Getters: return a zeroed/unchanged result for unowned bodies ----------

    @Inject(method = "getLinearVelocity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardGetLinearVelocity(PhysicsPipelineBody body, Vector3d dest,
                                            CallbackInfoReturnable<Vector3d> cir) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            cir.setReturnValue(target != null ? target.getLinearVelocity(body, dest) : dest.zero());
        }
    }

    @Inject(method = "getAngularVelocity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardGetAngularVelocity(PhysicsPipelineBody body, Vector3d dest,
                                             CallbackInfoReturnable<Vector3d> cir) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            cir.setReturnValue(target != null ? target.getAngularVelocity(body, dest) : dest.zero());
        }
    }

    // readPose's first param is ServerSubLevel (which implements PhysicsPipelineBody)
    // and it returns the dest Pose3d. Leave dest unchanged for an unowned body.
    @Inject(method = "readPose", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardReadPose(ServerSubLevel body, Pose3d dest,
                                   CallbackInfoReturnable<Pose3d> cir) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            if (target != null) {
                target.readPose(body, dest);
            }
            cir.setReturnValue(dest);
        }
    }

    // ---- void mutators: forward to the owning pipeline, or skip for true orphans ----

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardTeleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc orientation,
                                   CallbackInfo ci) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            if (target != null) target.teleport(body, position, orientation);
            ci.cancel();
        }
    }

    @Inject(method = "applyImpulse", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardApplyImpulse(PhysicsPipelineBody body, Vector3dc position, Vector3dc force,
                                       CallbackInfo ci) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            if (target != null) target.applyImpulse(body, position, force);
            ci.cancel();
        }
    }

    @Inject(method = "applyLinearAndAngularImpulse", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardApplyLinearAndAngularImpulse(PhysicsPipelineBody body, Vector3dc force, Vector3dc torque,
                                                       boolean wakeUp, CallbackInfo ci) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            if (target != null) target.applyLinearAndAngularImpulse(body, force, torque, wakeUp);
            ci.cancel();
        }
    }

    @Inject(method = "addLinearAndAngularVelocity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardAddVelocity(PhysicsPipelineBody body, Vector3dc linearVelocity, Vector3dc angularVelocity,
                                      CallbackInfo ci) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            if (target != null) target.addLinearAndAngularVelocity(body, linearVelocity, angularVelocity);
            ci.cancel();
        }
    }

    @Inject(method = "wakeUp", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardWakeUp(PhysicsPipelineBody body, CallbackInfo ci) {
        if (ipl$notOwned(body)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(body);
            if (target != null) target.wakeUp(body);
            ci.cancel();
        }
    }

    // onStatsChanged is called by the block-copy cascade during mirror spawn;
    // without this guard it would hand a mirror's stale id to native code.
    @Inject(method = "onStatsChanged", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardOnStatsChanged(ServerSubLevel serverSubLevel, CallbackInfo ci) {
        if (ipl$notOwned(serverSubLevel)) {
            RapierPhysicsPipeline target = ipl$forwardTarget(serverSubLevel);
            if (target != null) target.onStatsChanged(serverSubLevel);
            ci.cancel();
        }
    }

    // ---- Structural mutations: the JNI-abort paths (see class javadoc) ----------

    /**
     * {@code addConstraint(subA, subB, config)} -- the physics staff pins a
     * sub-level to the world (subA null) or two sub-levels together. If either
     * named sub-level has no native body (a mirror), {@code RapierFixedConstraintHandle.create}
     * feeds a stale id to {@code Rapier3D.addFixedConstraint} -> native panic ->
     * hard process abort. Return null (the method's own existing "can't make this
     * constraint" result; callers null-check the handle, e.g. via {@code isValid()}).
     */
    @Inject(method = "addConstraint", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private <T extends PhysicsConstraintHandle> void ipl$guardAddConstraint(
        @Nullable ServerSubLevel sublevelA, @Nullable ServerSubLevel sublevelB,
        PhysicsConstraintConfiguration<T> configuration, CallbackInfoReturnable<T> cir
    ) {
        if (ipl$notOwnedSub(sublevelA) || ipl$notOwnedSub(sublevelB)) {
            // Dim-agnostic forward (the physics staff resolves the player's dim's pipeline
            // while the grabbed body lives in the hosting pipeline). Both non-null ends must
            // live in the SAME owning pipeline; null means "the static world", which exists
            // in every scene.
            RapierPhysicsPipeline target = null;
            if (sublevelA != null) target = ipl$forwardTarget(sublevelA);
            if (target == null && sublevelB != null) target = ipl$forwardTarget(sublevelB);
            boolean sameScene = sublevelA == null || sublevelB == null
                || sublevelA.getLevel() == sublevelB.getLevel();
            if (target != null && sameScene) {
                ipl$logConstraintForward(sublevelA, sublevelB, configuration);
                cir.setReturnValue(target.addConstraint(sublevelA, sublevelB, configuration));
                return;
            }

            ServerSubLevel offender = ipl$notOwnedSub(sublevelA) ? sublevelA : sublevelB;
            IPL$LOG.warn("[IPL-RAPIER-GUARD] refused addConstraint on unowned sub-level {} "
                + "(no native body -- e.g. a kinematic mirror). Returning null handle.",
                offender != null ? offender.getUniqueId() : null);
            cir.setReturnValue(null);
        }
    }

    /**
     * {@code add(KinematicContraption)} -- enrolling a Create contraption that sits
     * on a sub-level. The method derives the mount sub-level from the contraption's
     * position and calls {@code Rapier3D.getID(mount)} -> {@code createKinematicContraption};
     * if the mount is a mirror, that's a stale id into native -> abort. Cancel the
     * enrollment entirely: a mirror's contraptions are not part of the physics scene
     * (the mirror itself isn't). Mirrors the mount lookup the original does
     * ({@code Sable.HELPER.getContaining(level, contraption.position())}) so we gate
     * on exactly the sub-level the original would have fed to native.
     */
    @Inject(method = "add(Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardAddContraption(KinematicContraption contraption, CallbackInfo ci) {
        SubLevel mount = Sable.HELPER.getContaining(this.level, contraption.sable$getPosition());
        if (mount instanceof ServerSubLevel serverMount && ipl$notOwnedSub(serverMount)) {
            // Dim-agnostic forward: a Create contraption assembling on a hosted airship must
            // enroll in the pipeline that owns the mount's body.
            RapierPhysicsPipeline target = ipl$forwardTarget(serverMount);
            if (target != null) {
                target.add(contraption);
                ci.cancel();
                return;
            }

            IPL$LOG.warn("[IPL-RAPIER-GUARD] refused add(KinematicContraption) on unowned mount "
                + "sub-level {} (no native body -- e.g. a kinematic mirror). Skipping enrollment.",
                serverMount.getUniqueId());
            ci.cancel();
        }
    }
}
