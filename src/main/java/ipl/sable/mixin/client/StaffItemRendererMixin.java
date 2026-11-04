package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import ipl.sable.client.IplStaffBeamRoutes;
import ipl.sable.client.IplStaffFocus;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Модель посоха в руке: ствол смотрит туда, куда идёт луч (в том числе через портал),
 * а фокус считается от настоящей камеры.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer", remap = false)
public abstract class StaffItemRendererMixin {

    // ---- Наводка ствола по лучу ----
    // Aim the held Creative Physics Staff where its BEAM goes, not at the raw native joint.
    //
    // <p>Stock computes the barrel aim from {@code renderPose().transformPosition(dragLocalAnchor)}
    // — the joint's native world position. When the joint is reached through a portal (a
    // cross-dimension grab, or a same-dimension construction held inside the portal) that position
    // is in the wrong place/dimension, so the staff points at nothing. We redirect that single
    // aim query to the target unfolded through the complete portal chain. The barrel aims
    // through the doorway as a window, without converging on its nearby surface.

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/companion/math/Pose3dc;transformPosition(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"
        )
    )
    private Vector3d ipl$aimAlongBeam(Pose3dc pose, Vector3d local, Operation<Vector3d> original) {
        PhysicsStaffClientHandler handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        LocalPlayer player = Minecraft.getInstance().player;
        if (session != null && player != null
            && session.dragSubLevel() instanceof ClientSubLevel sub) {
            Vector3dc anchor = session.dragLocalAnchor();
            Vec3 aim = IplStaffBeamRoutes.staffAimPoint(
                player, sub, new Vec3(anchor.x(), anchor.y(), anchor.z()),
                AnimationTickHolder.getPartialTicks()
            );
            // Item renderer normalizes this target into a barrel direction. Keep its
            // target in the eye's frame, as if every doorway were a window.
            if (aim != null) {
                if (aim.distanceToSqr(player.getEyePosition(AnimationTickHolder.getPartialTicks())) > 1.0e-8) {
                    return new Vector3d(aim.x, aim.y, aim.z);
                }
            }
        }
        return original.call(pose, local);
    }

    // ---- Кончик и камера от третьего лица ----

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
