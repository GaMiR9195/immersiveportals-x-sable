package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
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
        if (worldEq == null) return;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return;

        Pose3dc pose = sub.renderPose();
        Quaterniondc orientation = pose.orientation();

        // n_world (camera-relative in IP's storage convention)
        Vector3d worldNormal = new Vector3d(worldEq[0], worldEq[1], worldEq[2]);

        // Inverse-rotate into plot space: n_shader = R^-1 * n_world. Quaternion's
        // transformInverse does exactly that for a unit quaternion (which Sable's
        // orientations are -- they're rigid rotations on rigid bodies).
        Vector3d shaderNormal = new Vector3d();
        orientation.transformInverse(worldNormal, shaderNormal);

        // c is preserved because the matrix Sable uses has no translation;
        // translation is encoded in ChunkOffset which is on the position side of
        // the dot product, not the equation side.
        uniform.set(
            (float) shaderNormal.x,
            (float) shaderNormal.y,
            (float) shaderNormal.z,
            (float) worldEq[3]
        );
    }
}
