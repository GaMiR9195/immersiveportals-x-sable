package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.client.IplStaffPortalBeamRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.sugar.Local;

/** Veil only dispatches Simulated's beam hook in the main world; render in every IP world pass. */
@Mixin(LevelRenderer.class)
public abstract class IplStaffPortalBeamPassMixin {

    @Inject(
        method = "renderLevel",
        at = @At("TAIL"),
        require = 0
    )
    private void ipl$renderPhysicalStaffBeam(
        DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci,
        @Local PoseStack poseStack
    ) {
        IplStaffPortalBeamRenderer.render(poseStack, camera);
    }
}
