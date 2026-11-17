package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.HardcodedCustomUniforms;
import net.irisshaders.iris.uniforms.IrisExclusiveUniforms;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewSpace;

/** Camera-entity uniforms are read where the player appears in the view being rendered. */
@Mixin(value = {CommonUniforms.class, HardcodedCustomUniforms.class, IrisExclusiveUniforms.class}, remap = false)
public class MixinIrisViewSpaceUniforms {
    @WrapOperation(
        method = "*", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;position()Lnet/minecraft/world/phys/Vec3;")
    )
    private static Vec3 ip_position(Entity entity, Operation<Vec3> original) {
        return IrisViewSpace.toView(original.call(entity));
    }

    @WrapOperation(
        method = "*", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyeY()D")
    )
    private static double ip_eyeY(Entity entity, Operation<Double> original) {
        return IrisViewSpace.eyeY(entity, original.call(entity));
    }

    @WrapOperation(
        method = "*", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;")
    )
    private static BlockPos ip_blockPosition(Entity entity, Operation<BlockPos> original) {
        return IrisViewSpace.blockPosition(entity, original.call(entity));
    }

    @WrapOperation(
        method = "*", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;")
    )
    private static Vec3 ip_eyePosition(Entity entity, float partialTick, Operation<Vec3> original) {
        return IrisViewSpace.toView(original.call(entity, partialTick));
    }
}
