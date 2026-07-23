package dev.simulated_team.simulated.mixin.end_sea;

import dev.simulated_team.simulated.content.end_sea.EndSeaRenderer;
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

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V"))
    public void renderLevel(final DeltaTracker deltaTracker, final boolean bl, final Camera camera, final GameRenderer gameRenderer, final LightTexture lightTexture, final Matrix4f matrix4f, final Matrix4f matrix4f2, final CallbackInfo ci) {
        EndSeaRenderer.render(camera, gameRenderer);
    }
    
}
