package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.compat.sable.SableShadowCompat;
import top.leonx.irisveil.compat.veil.RenderStateManager;

/**
 * Tracks the Iris shadow render pass so that Veil shaders can be compiled
 * with the correct Iris program ({@code ProgramId.Shadow}) instead of the
 * default block program.
 *
 * <p>Veil draw calls can still run from render-stage hooks while Iris is
 * rendering the shadow map. The full {@code renderShadows()} method must be
 * treated as a shadow pass even when the shaderpack disables block-entity
 * shadow iteration, otherwise those draws keep using gbuffer shaders.
 */
@Mixin(value = ShadowRenderer.class)
public abstract class MixinShadowRenderer {

    @Unique
    private boolean irisveil$sableShadowBlockEntitiesAttempted;

    @Final
    @Shadow
    private boolean shouldRenderBlockEntities;

    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void irisveil$onShadowPassStart(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        irisveil$sableShadowBlockEntitiesAttempted = false;
        RenderStateManager.beginShadowPass(shouldRenderBlockEntities);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/batchedentityrendering/impl/FullyBufferedMultiBufferSource;readyUp()V",
            shift = At.Shift.BEFORE),
        require = 0)
    private void irisveil$renderSableShadowBlockEntitiesBeforeReadyUp(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        irisveil$renderSableShadowBlockEntities(levelRendererAccessor);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
            shift = At.Shift.BEFORE))
    private void irisveil$renderSableShadowBlockEntitiesBeforeEndBatch(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        irisveil$renderSableShadowBlockEntities(levelRendererAccessor);
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void irisveil$onShadowPassEnd(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        RenderStateManager.endShadowPass();
    }

    @Unique
    private void irisveil$renderSableShadowBlockEntities(LevelRendererAccessor levelRendererAccessor) {
        if (irisveil$sableShadowBlockEntitiesAttempted || !shouldRenderBlockEntities) {
            return;
        }
        irisveil$sableShadowBlockEntitiesAttempted = true;

        Vector3d cameraPosition = CameraUniforms.getUnshiftedCameraPosition();
        SableShadowCompat.renderSubLevelBlockEntities(
            levelRendererAccessor.getLevel(),
            levelRendererAccessor.getRenderBuffers(),
            Minecraft.getInstance().getBlockEntityRenderDispatcher(),
            ShadowRenderer.MODELVIEW,
            cameraPosition.x(),
            cameraPosition.y(),
            cameraPosition.z(),
            CapturedRenderingState.INSTANCE.getTickDelta());
    }
}
