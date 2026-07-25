package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

@Mixin(LevelRenderer.class)
public class MixinLevelRendererCompatHooks {
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER
        )
    )
    private void irisveil$renderCompatWorldHooks(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f modelView,
        Matrix4f projection,
        CallbackInfo ci) {
        if (!IrisVeilCompat.isShaderPackInUse()) {
            return;
        }

        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipelineAccessor accessor)) {
            return;
        }

        VeilCompatRegistry.renderWorldHooks(
            camera,
            gameRenderer,
            drawBuffers -> {
                accessor.irisveil$bindCompatGbufferFramebuffer(drawBuffers);
                return true;
            },
            () -> Minecraft.getInstance().getMainRenderTarget().bindWrite(false));
    }
}
