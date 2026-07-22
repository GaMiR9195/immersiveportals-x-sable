package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Frame-correct staff drag/lock RENDERING (beam targets, anchor markers) for foreign
 * straddlers: render-pose reads in the staff's render handler and item renderer
 * substitute the portal-mapped pose so visuals land at the through-part. Same pattern as
 * the client-handler wraps in {@code IplStaffPickMixin}.
 */
@Pseudo
@Mixin(
    targets = {
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffRenderHandler",
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer"
    },
    remap = false
)
public abstract class IplStaffRenderPoseMixin {

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            remap = false
        ),
        require = 0
    )
    private static dev.ryanhcode.sable.companion.math.Pose3dc ipl$mapRenderPose(
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
        Operation<dev.ryanhcode.sable.companion.math.Pose3dc> original
    ) {
        dev.ryanhcode.sable.companion.math.Pose3dc pose = original.call(sub);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return pose;
        net.minecraft.core.BlockPos offset =
            ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, mc.level);
        return offset != null ? ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset) : pose;
    }

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            remap = false
        ),
        require = 0
    )
    private static dev.ryanhcode.sable.companion.math.Pose3dc ipl$mapRenderPosePt(
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub, float pt,
        Operation<dev.ryanhcode.sable.companion.math.Pose3dc> original
    ) {
        dev.ryanhcode.sable.companion.math.Pose3dc pose = original.call(sub, pt);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return pose;
        net.minecraft.core.BlockPos offset =
            ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, mc.level);
        return offset != null ? ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset) : pose;
    }
}
