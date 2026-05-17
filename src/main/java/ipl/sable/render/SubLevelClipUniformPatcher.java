package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.ShaderInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Translates IP's world-space clip equation into the per-sub-level shader space
 * Sable's chunked renderer uses, then writes it directly to the
 * {@code iportal_ClippingEquation} uniform.
 *
 * <p><b>Why this is necessary:</b> IP's {@code rendertype_solid/cutout} shader
 * transformation injects the discard as
 * <pre>
 *   gl_ClipDistance[0] = dot(Position.xyz + ChunkOffset, iportal_ClippingEquation.xyz)
 *                      + iportal_ClippingEquation.w;
 * </pre>
 * For vanilla terrain, {@code Position + ChunkOffset} is camera-relative
 * world-space, so the world-space equation IP sets via
 * {@link FrontClipping#updateClippingEquationUniformForCurrentShader} works
 * directly.
 *
 * <p>For Sable's sub-level renderer, the {@code MODEL_VIEW_MATRIX} is overridden
 * inside {@code VanillaChunkedSubLevelRenderData.renderChunkedSubLevel} to be a
 * pure rotation by the sub-level's orientation (R), with translation folded into
 * each section's {@code CHUNK_OFFSET}. So in the shader,
 * {@code Position + ChunkOffset} is in <b>plot space, inverse-rotated and
 * camera-shifted</b> -- not world space. The shader's
 * {@code R · (Position + ChunkOffset)} produces camera-relative world coords;
 * the discard runs <i>before</i> that multiplication.
 *
 * <p>For the shader-space discard to match the world-space discard,
 * {@code n_shader = R⁻¹ · n_world}, {@code c_shader = c_world}
 * (translation in the camera/section composition doesn't enter the equation
 * because it's applied as part of the rotation matrix, which is orthonormal).
 * That's what {@link #patchForSubLevel} computes and pushes to the active
 * shader's clip uniform, replacing the world-space equation IP wrote.
 *
 * <p>Restoring is handled by callers via
 * {@link FrontClipping#unsetClippingUniform()} on RETURN.
 */
public final class SubLevelClipUniformPatcher {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip-patch");
    private static volatile long lastReportNanos = 0L;

    /**
     * When set to "true" via -Dipl.sable.clip.forceAll=true on the JVM cmdline, override the
     * equation with (0, 0, 0, -1) -- which forces every fragment past the discard to
     * evaluate to -1, clipping all geometry. Used as a diagnostic: if airship still
     * renders with this set, the uniform isn't reaching the shader at all (rather than
     * the math being wrong).
     */
    private static final boolean FORCE_CLIP_ALL =
        Boolean.getBoolean("ipl.sable.clip.forceAll");

    static {
        // Emit at class init so users can confirm the JVM property took effect.
        LOG.info("[IPL-CLIP-PATCH] static init: FORCE_CLIP_ALL={}", FORCE_CLIP_ALL);
    }

    private static volatile long lastEarlyReturnNanos = 0L;
    private static volatile String lastEarlyReturnReason = "";

    private static void logEarlyReturn(String reason) {
        // De-dupe by reason text so a different early-return path isn't suppressed.
        long now = System.nanoTime();
        if (!reason.equals(lastEarlyReturnReason) || now - lastEarlyReturnNanos >= 5_000_000_000L) {
            lastEarlyReturnReason = reason;
            lastEarlyReturnNanos = now;
            LOG.warn("[IPL-CLIP-PATCH] early return: {}", reason);
        }
    }

    private SubLevelClipUniformPatcher() {}

    /**
     * Reads IP's currently-installed world-space equation (must be called after
     * {@link FrontClipping#setupInnerClipping} has populated
     * {@code activeClipPlaneEquationBeforeModelView}), rotates the normal by the
     * inverse of {@code sub}'s render-pose orientation, and writes the result to
     * the active shader's clip uniform.
     */
    public static void patchForSubLevel(ClientSubLevel sub) {
        double[] worldEq = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        if (worldEq == null) {
            logEarlyReturn("worldEq null");
            return;
        }

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            logEarlyReturn("RenderSystem.getShader() null");
            return;
        }
        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) {
            logEarlyReturn("shader '" + shader.getName() + "' has no iportal_ClippingEquation uniform "
                + "(IP's MixinShaderInstance didn't add it -- check ShaderCodeTransformation affectedShaders list)");
            return;
        }

        // With the Iris-style shader transformation added in
        // shader_transformation.yaml, the per-sub-level shader computes the
        // discard against `gbufferModelViewInverse * iris_ModelViewMat *
        // (iris_Position + iris_ChunkOffset)` -- i.e., world-space relative to
        // camera. So the equation goes through as IP's world-space form
        // without rotation. The reason we still need this patcher is that IP's
        // `updateClippingEquationUniformForCurrentShader` only calls Uniform.set()
        // and relies on `shader.apply()` to flush, which doesn't re-run between
        // our mixin's HEAD inject and the actual draw inside renderChunkedSubLevel.
        // So we set + upload here to force the world equation onto the GPU.
        @SuppressWarnings("unused") Pose3dc pose = sub.renderPose();

        float nx, ny, nz, cw;
        if (FORCE_CLIP_ALL) {
            // Diagnostic mode: force every vertex to evaluate to -1, clipping all
            // geometry. If the airship STILL renders with this active, the uniform
            // isn't reaching the GPU at all.
            nx = 0f; ny = 0f; nz = 0f; cw = -1f;
        } else {
            nx = (float) worldEq[0];
            ny = (float) worldEq[1];
            nz = (float) worldEq[2];
            cw = (float) worldEq[3];
        }

        uniform.set(nx, ny, nz, cw);

        // Explicit upload. IP's standard FrontClipping.updateClippingEquationUniformForCurrentShader
        // only calls set() and relies on the surrounding render path to flush uniforms via
        // shader.apply(). Sable's renderChunkedSubLevel doesn't re-apply the shader after
        // our HEAD inject -- it sets and uploads only MODEL_VIEW_MATRIX and per-section
        // CHUNK_OFFSET. So without this explicit upload, our changed equation lives in CPU
        // memory while the GPU keeps using whatever was last uploaded (typically zero, set
        // during the prior shader apply via IP's setShader hook). Forcing the upload here
        // makes the discard actually fire against our equation.
        uniform.upload();

        // Diagnostic: every 5s, log the values we're pushing so we can sanity-check
        // that the equation looks reasonable and the shader has the uniform.
        long now = System.nanoTime();
        if (now - lastReportNanos >= 5_000_000_000L) {
            lastReportNanos = now;
            LOG.info("[IPL-CLIP-PATCH] shader={} forceAll={} worldEq=({},{},{},{}) wrote=({},{},{},{})",
                shader.getName(),
                FORCE_CLIP_ALL,
                worldEq[0], worldEq[1], worldEq[2], worldEq[3],
                nx, ny, nz, cw
            );
        }
    }

    /**
     * Restore the uniform to "no clipping" and push to the GPU. Used by mixin RETURN
     * paths so subsequent draws (vanilla terrain after our sub-level render) don't
     * inherit our plot-space equation, which would clip them at meaningless
     * coordinates (~10,000-block-offset planes).
     */
    public static void clearAndUpload() {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return;
        uniform.set(0f, 0f, 0f, 1f);
        uniform.upload();
    }
}
