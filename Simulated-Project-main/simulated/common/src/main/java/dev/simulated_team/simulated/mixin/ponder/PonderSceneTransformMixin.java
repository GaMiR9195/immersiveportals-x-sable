package dev.simulated_team.simulated.mixin.ponder;

import com.llamalad7.mixinextras.sugar.Local;
import dev.simulated_team.simulated.mixin_interface.ponder.PonderSceneExtension;
import net.createmod.ponder.foundation.PonderScene;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PonderScene.SceneTransform.class)
public abstract class PonderSceneTransformMixin {
    // todo pr create to interpolate these variables
    @Redirect(method = "apply(Lcom/mojang/blaze3d/vertex/PoseStack;F)Lcom/mojang/blaze3d/vertex/PoseStack;", at = @At(value = "FIELD", target = "Lnet/createmod/ponder/foundation/PonderScene;scaleFactor:F"))
    private float interpolateScaleFactor(final PonderScene instance, @Local(argsOnly = true) final float pt) {
        return ((PonderSceneExtension)instance).simulated$getScale(pt);
    }

    @Redirect(method = "apply(Lcom/mojang/blaze3d/vertex/PoseStack;F)Lcom/mojang/blaze3d/vertex/PoseStack;", at = @At(value = "FIELD", target = "Lnet/createmod/ponder/foundation/PonderScene;yOffset:F"))
    private float interpolateYOffset(final PonderScene instance, @Local(argsOnly = true) final float pt) {
        return ((PonderSceneExtension)instance).simulated$getYOffset(pt);
    }
}
