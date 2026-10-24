package ipl.sable.mixin;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplCollisionCallbackContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class IplContactSteppingSystemMixin {
    @Inject(method = "getCurrentlySteppingSystem", at = @At("HEAD"), cancellable = true, require = 1)
    private static void ipl$contactSystem(CallbackInfoReturnable<SubLevelPhysicsSystem> cir) {
        var level = IplCollisionCallbackContext.current();
        if (level != null) cir.setReturnValue(SubLevelPhysicsSystem.require(level));
    }
}
