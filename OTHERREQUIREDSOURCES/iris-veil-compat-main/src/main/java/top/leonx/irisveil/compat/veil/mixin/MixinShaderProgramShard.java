package top.leonx.irisveil.compat.veil.mixin;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.impl.client.render.pipeline.ShaderProgramShard;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.IrisVeilShaderCache;
import top.leonx.irisveil.compat.veil.RenderStateManager;
import top.leonx.irisveil.compat.veil.VeilDefaultUniforms;

/**
 * Intercepts {@link ShaderProgramShard#setupRenderState()} at runtime.
 * When Iris is active, lets Veil set its normal render state first, then
 * switches the active program to an Iris {@link ShaderInstance} that outputs
 * to gbuffer.
 *
 * <p>This mixin approach avoids inheritance issues with the
 * {@code ShaderStateShard} API which varies between Minecraft versions.
 */
@Mixin(value = ShaderProgramShard.class, remap = false)
public class MixinShaderProgramShard {

    @Unique
    private volatile ShaderInstance irisveil$cachedIrisShader;

    @Unique
    private volatile boolean irisveil$cachedIsShadow;

    @Unique
    private volatile int irisveil$lastShaderPackGen = -1;

    @Unique
    private volatile int irisveil$cachedExternalRenderStateGen = -1;

    @Inject(method = "setupRenderState", at = @At("TAIL"))
    private void irisveil$interceptSetupRenderState(CallbackInfo ci) {
        if (!IrisVeilCompat.isShaderPackInUse()) {
            return; // no shaderpack loaded → let Veil handle it
        }

        ResourceLocation shaderPath = ((ShaderProgramShard) (Object) this).getShader();

        try {
            ShaderInstance irisShader = irisveil$getOrCreateIrisShader(shaderPath);
            if (irisShader != null) {
                irisShader.apply();
                VeilDefaultUniforms.uploadBlockFaceBrightness(irisShader);
                RenderSystem.setShader(() -> irisShader); // register in RenderSystem so draw uses Iris shader

                // During the shadow pass, irisShader.apply() uploads gbuffer
                // matrices by default.  Override with the shadow-specific
                // projection / model-view so the shader writes correct depth.
                if (RenderStateManager.isRenderingShadow()) {
                    irisveil$setShadowMatrices(irisShader);
                }
            }
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.warn(
                "IrisVeilCompat: failed for '{}', falling back to Veil", shaderPath, e);
            // Invalidate so we retry next frame (Veil shader may have been compiled lazily)
            irisveil$cachedIrisShader = null;
        }
    }

    /**
     * Sets {@code iris_ProjMat}, {@code iris_ModelViewMat}, and
     * {@code iris_NormalMat} on the given shader to the shadow pass
     * matrices tracked by {@link ShadowRenderer}.
     */
    @Unique
    private static void irisveil$setShadowMatrices(ShaderInstance shader) {
        Uniform projUniform = shader.getUniform("iris_ProjMat");
        if (projUniform != null) {
            projUniform.set(ShadowRenderer.PROJECTION);
        }

        Uniform modelViewUniform = shader.getUniform("iris_ModelViewMat");
        if (modelViewUniform != null) {
            modelViewUniform.set(ShadowRenderer.MODELVIEW);
        }

        Uniform normalUniform = shader.getUniform("iris_NormalMat");
        if (normalUniform != null) {
            Matrix4f normalMatrix = new Matrix4f(ShadowRenderer.MODELVIEW);
            normalMatrix.invert();
            normalMatrix.transpose();
            normalUniform.set(new Matrix3f(normalMatrix));
        }
    }

    @Unique
    private ShaderInstance irisveil$getOrCreateIrisShader(ResourceLocation shaderPath) {
        int currentGen = IrisVeilShaderCache.getShaderPackGeneration();
        boolean isShadow = RenderStateManager.isRenderingShadow();
        int externalRenderStateGen = IrisVeilShaderCache.getExternalRenderStateGeneration();

        // Shaderpack changed OR render pass changed (shadow ↔ non-shadow)
        // OR an external render state changed → create or bypass the correct shader.
        if (irisveil$lastShaderPackGen != currentGen
            || irisveil$cachedIsShadow != isShadow
            || irisveil$cachedExternalRenderStateGen != externalRenderStateGen) {
            irisveil$cachedIrisShader = null;
        }

        // Use cached result only if valid (non-null) and state matches
        if (irisveil$cachedIrisShader != null
            && irisveil$lastShaderPackGen == currentGen
            && irisveil$cachedExternalRenderStateGen == externalRenderStateGen) {
            return irisveil$cachedIrisShader;
        }

        irisveil$cachedIrisShader = IrisVeilShaderCache.getOrCreate(shaderPath);
        irisveil$lastShaderPackGen = currentGen;
        irisveil$cachedIsShadow = isShadow;
        irisveil$cachedExternalRenderStateGen = externalRenderStateGen;
        return irisveil$cachedIrisShader;
    }
}
