package dev.simulated_team.simulated.mixin.ponder;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.Rotate;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldSectionElementImpl.class)
public class WorldSectionElementImplMixin {

    @WrapOperation(method = "transformMS", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;rotateYDegrees(F)Ldev/engine_room/flywheel/lib/transform/Rotate;", ordinal = 0))
    public Rotate<PoseTransformStack> fixRotateY(final PoseTransformStack instance, final float v, final Operation<Rotate<PoseTransformStack>> original, @Local(ordinal = 1) final double rotZ) {
        return instance.rotateZDegrees((float) rotZ);
    }

    @WrapOperation(method = "transformMS", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;rotateZDegrees(F)Ldev/engine_room/flywheel/lib/transform/Rotate;", ordinal = 0))
    public Rotate<PoseTransformStack> fixRotateZ(final PoseTransformStack instance, final float v, final Operation<Rotate<PoseTransformStack>> original, @Local(ordinal = 2) final double rotY) {
        return instance.rotateYDegrees((float) rotY);
    }

    @WrapOperation(method = "transformMS", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;rotateYDegrees(F)Ldev/engine_room/flywheel/lib/transform/Rotate;", ordinal = 1))
    public Rotate<PoseTransformStack> fixUnRotateY(final PoseTransformStack instance, final float v, final Operation<Rotate<PoseTransformStack>> original, @Local(ordinal = 1) final double rotZ) {
        return instance.rotateZDegrees((float) -rotZ);
    }

    @WrapOperation(method = "transformMS", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;rotateZDegrees(F)Ldev/engine_room/flywheel/lib/transform/Rotate;", ordinal = 1))
    public Rotate<PoseTransformStack> fixUnRotateZ(final PoseTransformStack instance, final float v, final Operation<Rotate<PoseTransformStack>> original, @Local(ordinal = 2) final double rotY) {
        return instance.rotateYDegrees((float) -rotY);
    }
}
