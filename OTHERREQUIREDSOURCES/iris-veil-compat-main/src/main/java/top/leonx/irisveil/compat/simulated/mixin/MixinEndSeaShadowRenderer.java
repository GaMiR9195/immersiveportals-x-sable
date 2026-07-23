package top.leonx.irisveil.compat.simulated.mixin;

import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.simulated.SimulatedEndSeaCompat;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.end_sea.EndSeaShadowRenderer", remap = false)
public class MixinEndSeaShadowRenderer {
    @Inject(
        method = "renderShadowMap",
        at = @At(
            value = "INVOKE",
            target = "Lfoundry/veil/api/client/render/framebuffer/AdvancedFbo;bind(Z)V",
            shift = At.Shift.BEFORE,
            remap = false
        ),
        remap = false,
        require = 0
    )
    private static void irisveil$prepareEndSeaShadowMapState(
        VeilRenderLevelStageEvent.Stage stage,
        LevelRenderer levelRenderer,
        MultiBufferSource.BufferSource bufferSource,
        MatrixStack matrixStack,
        Matrix4fc frustumMatrix,
        Matrix4fc projectionMatrix,
        int renderTick,
        DeltaTracker deltaTracker,
        Camera camera,
        Frustum frustum,
        CallbackInfo ci
    ) {
        if (IrisVeilCompat.isShaderPackInUse()) {
            SimulatedEndSeaCompat.prepareShadowMapRenderState();
        }
    }
}
