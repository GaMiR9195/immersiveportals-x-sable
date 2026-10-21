package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/** Preserve Simulated's focus math; IP's entity-visibility hook is not a camera mode. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class IplStaffFocusMixin {
    @WrapOperation(method = "getStaffFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getMainCamera()Lnet/minecraft/client/Camera;"), require = 1)
    private static Camera ipl$rootCamera(GameRenderer renderer, Operation<Camera> original) {
        return WorldRenderInfo.isRendering() && RenderStates.originalCamera != null
            ? RenderStates.originalCamera : original.call(renderer);
    }

    @WrapOperation(method = "getStaffFocusPos", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;isDetached()Z"), require = 1)
    private static boolean ipl$physicalCameraMode(Camera camera, Operation<Boolean> original) {
        // MixinCamera.onIsThirdPerson returns true to show players THROUGH portals,
        // even on originalCamera. Calling it here selected the body-space origin
        // and cached that wrong origin for the entire physical beam frame.
        return !Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    @Inject(method = "onRender", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$ownedBeamPass(PoseStack poses, CallbackInfo ci) {
        // The NeoForge stage renders every beam. Do not run stock's global Catnip
        // draw() here: it can flush another world's pending selection effects.
        ci.cancel();
    }
}
