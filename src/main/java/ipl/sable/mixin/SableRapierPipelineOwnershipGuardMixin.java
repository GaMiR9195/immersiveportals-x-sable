package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
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

    @Shadow @Final private Int2ObjectMap<ServerSubLevel> activeSubLevels;

    /** True when the pipeline has no native rigid body for this sub-level. */
    private boolean ipl$notOwned(PhysicsPipelineBody body) {
        return body == null || !this.activeSubLevels.containsKey(body.getRuntimeId());
    }

    // ---- Getters: return a zeroed/unchanged result for unowned bodies ----------

    @Inject(method = "getLinearVelocity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardGetLinearVelocity(PhysicsPipelineBody body, Vector3d dest,
                                            CallbackInfoReturnable<Vector3d> cir) {
        if (ipl$notOwned(body)) cir.setReturnValue(dest.zero());
    }

    @Inject(method = "getAngularVelocity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardGetAngularVelocity(PhysicsPipelineBody body, Vector3d dest,
                                             CallbackInfoReturnable<Vector3d> cir) {
        if (ipl$notOwned(body)) cir.setReturnValue(dest.zero());
    }

    // readPose's first param is ServerSubLevel (which implements PhysicsPipelineBody)
    // and it returns the dest Pose3d. Leave dest unchanged for an unowned body.
    @Inject(method = "readPose", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardReadPose(ServerSubLevel body, Pose3d dest,
                                   CallbackInfoReturnable<Pose3d> cir) {
        if (ipl$notOwned(body)) cir.setReturnValue(dest);
    }

    // ---- void mutators: skip entirely for unowned bodies -----------------------

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardTeleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc orientation,
                                   CallbackInfo ci) {
        if (ipl$notOwned(body)) ci.cancel();
    }

    @Inject(method = "applyImpulse", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardApplyImpulse(PhysicsPipelineBody body, Vector3dc position, Vector3dc force,
                                       CallbackInfo ci) {
        if (ipl$notOwned(body)) ci.cancel();
    }

    @Inject(method = "applyLinearAndAngularImpulse", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardApplyLinearAndAngularImpulse(PhysicsPipelineBody body, Vector3dc force, Vector3dc torque,
                                                       boolean wakeUp, CallbackInfo ci) {
        if (ipl$notOwned(body)) ci.cancel();
    }

    @Inject(method = "addLinearAndAngularVelocity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardAddVelocity(PhysicsPipelineBody body, Vector3dc linearVelocity, Vector3dc angularVelocity,
                                      CallbackInfo ci) {
        if (ipl$notOwned(body)) ci.cancel();
    }

    @Inject(method = "wakeUp", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardWakeUp(PhysicsPipelineBody body, CallbackInfo ci) {
        if (ipl$notOwned(body)) ci.cancel();
    }

    // onStatsChanged is called by the block-copy cascade during mirror spawn;
    // without this guard it would hand a mirror's stale id to native code.
    @Inject(method = "onStatsChanged", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$guardOnStatsChanged(ServerSubLevel serverSubLevel, CallbackInfo ci) {
        if (ipl$notOwned(serverSubLevel)) ci.cancel();
    }
}
