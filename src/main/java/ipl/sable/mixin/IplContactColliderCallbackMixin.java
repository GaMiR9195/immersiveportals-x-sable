package ipl.sable.mixin;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import ipl.sable.dim.IplCollisionCallbackContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
/** Wrap the Java factory, never attempt injection into a native method's absent body. */
@Mixin(value = Rapier3D.class, remap = false)
public abstract class IplContactColliderCallbackMixin {
    @ModifyVariable(method = "createVoxelColliderEntry", at = @At("HEAD"), argsOnly = true, require = 1)
    private static BlockSubLevelCollisionCallback ipl$contactOwner(BlockSubLevelCollisionCallback callback) {
        return IplCollisionCallbackContext.wrap(callback);
    }
}
