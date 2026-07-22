package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Frame-correct staff DRAG goal for foreign straddlers.
 *
 * <p>The drag session's PD motor goal is assembled as
 * {@code playerRelativeGoal + playerEye} — the eye in the PLAYER's dimension frame —
 * then rotated into constraint space and fed to motors driving a body that lives at
 * SOURCE-frame coordinates in the hosting scene. For a player dragging a straddler's
 * through-part from the dest side, the unmapped goal is off by the full portal offset:
 * the motors would yank the ship ~135 blocks toward the misinterpreted target (the
 * "fling" hazard). The relative-goal component is frame-free (a pure offset), so the fix
 * is exactly one subtraction: map the assembled world-frame goal into the ship's parent
 * frame before the constraint-space rotation.
 *
 * <p>Server {@code logicalPose} reads elsewhere in the handler (constraint anchor
 * construction) are deliberately NOT mapped — they are body-frame by design.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public abstract class IplStaffDragGoalMixin {

    @Shadow(remap = false)
    @Final
    private ServerSubLevel subLevel;

    @WrapOperation(
        method = "physicsTick",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Quaterniond;transformInverse(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"
        ),
        require = 0
    )
    private Vector3d ipl$mapDragGoalFrame(
        Quaterniond orientation, Vector3d worldFrameGoal, Operation<Vector3d> original,
        @Local Player player
    ) {
        // worldFrameGoal is in the PLAYER's dimension frame here; the body is in the
        // ship's PARENT frame. Subtract the portal offset for foreign straddlers.
        BlockPos offset = IplStraddlePoseMap.getOffsetInto(this.subLevel, player.level());
        if (offset != null) {
            worldFrameGoal.sub(offset.getX(), offset.getY(), offset.getZ());
        }
        return original.call(orientation, worldFrameGoal);
    }
}
