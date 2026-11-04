package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.atlas.IplFusedStep;
import ipl.sable.duck.IplPhysicsStepClock;
import ipl.sable.transit.IplShipPortalAnchor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Physics stepping through {@link IplFusedStep}: one step of the shared world per server tick
 * under IPSable's natives, Sable's own per-level step with stock natives.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class PhysicsSystemMixin implements IplPhysicsStepClock {

    @Unique
    private int ipl$steppedTick = Integer.MIN_VALUE;

    @Override
    public int ipl$steppedTick() {
        return ipl$steppedTick;
    }

    @Override
    public void ipl$markStepped(int serverTick) {
        ipl$steppedTick = serverTick;
    }

    /** A level the fused driver already stepped stored its last pose before that step. */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;updateLastPose()V"),
        require = 1
    )
    private void ipl$keepPreStepLastPose(ServerSubLevel subLevel, Operation<Void> original) {
        if (!IplFusedStep.steppedThisTick((SubLevelPhysicsSystem) (Object) this)) {
            original.call(subLevel);
        }
    }

    @Inject(method = "tickPipelinePhysics", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$fuseStep(ServerSubLevelContainer container, CallbackInfo ci) {
        if (IplFusedStep.onTickPipelinePhysics((SubLevelPhysicsSystem) (Object) this, container)) {
            ci.cancel();
        }
    }

    /** Reached only by the stock per-level step: anchored portals follow this level's new poses. */
    @Inject(method = "tickPipelinePhysics", at = @At("TAIL"))
    private void ipl$afterStockStep(ServerSubLevelContainer container, CallbackInfo ci) {
        IplShipPortalAnchor.tickLevel(((SubLevelPhysicsSystem) (Object) this).getLevel());
    }
}
