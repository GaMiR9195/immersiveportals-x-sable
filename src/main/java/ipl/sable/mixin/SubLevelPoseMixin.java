package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplContraptionCollision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While a contraption piece is collided, its body stands at that piece's pose ({@link IplContraptionCollision}). */
@Mixin(value = SubLevel.class, remap = false)
public abstract class SubLevelPoseMixin {

    @ModifyReturnValue(method = "logicalPose", at = @At("RETURN"))
    private Pose3d ipl$pieceLogicalPose(Pose3d original) {
        return IplContraptionCollision.logicalPose((SubLevel) (Object) this, original);
    }

    @ModifyReturnValue(method = "lastPose", at = @At("RETURN"))
    private Pose3dc ipl$pieceLastPose(Pose3dc original) {
        return IplContraptionCollision.lastPose((SubLevel) (Object) this, original);
    }
}
