package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import ipl.sable.client.IplStaffFocus;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer", remap = false)
public abstract class IplStaffItemFocusMixin {
    @WrapOperation(method = "getFirstPersonFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getMainCamera()Lnet/minecraft/client/Camera;"), require = 1)
    private static Camera ipl$rootCamera(GameRenderer renderer, Operation<Camera> original) {
        return WorldRenderInfo.isRendering() && RenderStates.originalCamera != null
            ? RenderStates.originalCamera : original.call(renderer);
    }

    /**
     * Capture the actual third-person model tip after Simulated's final 6/16 translation.
     * Entity item matrices are camera-relative, so add the physical camera position.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            ordinal = 3,
            shift = At.Shift.AFTER
        ),
        require = 1
    )
    private void ipl$captureThirdPersonTip(
        ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
        ItemDisplayContext context, PoseStack poses, MultiBufferSource buffer,
        int light, int overlay, CallbackInfo ci
    ) {
        if (context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND
            && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return;

        Vector3f relative = poses.last().pose().transformPosition(new Vector3f());
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        IplStaffFocus.capture(stack, new Vec3(
            relative.x + cameraPos.x,
            relative.y + cameraPos.y,
            relative.z + cameraPos.z
        ));
    }
}
