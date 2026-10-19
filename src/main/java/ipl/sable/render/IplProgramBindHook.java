package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import java.util.HashMap;
import java.util.Map;

/** Render-thread upload to the program actually used by a draw, never a registry broadcast. */
public final class IplProgramBindHook {
    private static final Map<Integer, int[]> LOCATIONS = new HashMap<>();
    private IplProgramBindHook() {}

    private static int[] locations(int program) {
        return LOCATIONS.computeIfAbsent(program, p -> new int[]{
            GL20.glGetUniformLocation(p, "iportal_ClippingEquation"),
            GL20.glGetUniformLocation(p, "ipl_subLevelClipEquation"),
            GL20.glGetUniformLocation(p, "ipl_subLevelClipEquation[1]")
        });
    }

    /** Called on relink/deletion: GL may reuse a numeric program name. */
    public static void forgetProgram(int program) {
        LOCATIONS.remove(program);
    }

    public static void onBind(int program) {
        if (program <= 0) return;
        boolean portalClip = FrontClipping.isClippingEnabled;
        boolean portalPass = PortalRendering.isRendering();
        boolean subClip = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld() != null;
        if (!portalClip && !portalPass && !subClip) return;
        int[] locs = locations(program);
        if (portalClip) IplClipEquationCache.refreshFromActive();
        if (portalClip || portalPass) GL11.glEnable(GL11.GL_CLIP_PLANE0);
        if (locs[0] >= 0 && (portalClip || portalPass)) {
            boolean eye = IplProgramRegistry.isEntityStyleProgram(program);
            double[] eq = eye
                ? (portalClip ? FrontClipping.getActiveClipPlaneEquationAfterModelView() : IplClipEquationCache.getEyeEq())
                : (portalClip ? FrontClipping.getActiveClipPlaneEquationBeforeModelView() : IplClipEquationCache.getWorldEq());
            if (eq != null) GL41.glProgramUniform4f(program, locs[0],
                (float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
        }
        if (subClip) {
            uploadSubLevel(program);
            if (locs[1] >= 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
            if (locs[2] >= 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE2);
        }
    }

    private static float[] equation(int program, boolean second) {
        if (IplProgramRegistry.usesVanillaSubLevelInputSpace(program)) {
            return second ? SubLevelClipUniformPatcher.getCurrentSubLevelEqVanillaInput2()
                : SubLevelClipUniformPatcher.getCurrentSubLevelEqVanillaInput();
        }
        if (IplProgramRegistry.isEntityStyleProgram(program)) {
            return second ? SubLevelClipUniformPatcher.getCurrentSubLevelEqEye2()
                : SubLevelClipUniformPatcher.getCurrentSubLevelEqEye();
        }
        return second ? SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld2()
            : SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld();
    }

    /** Covers an already-bound program at bracket entry, without touching other programs. */
    public static void uploadSubLevel(int program) {
        if (program <= 0) return;
        int[] locs = locations(program);
        write(program, locs[1], equation(program, false));
        write(program, locs[2], equation(program, true));
    }

    private static void write(int program, int location, float[] eq) {
        if (location < 0) return;
        if (eq == null) GL41.glProgramUniform4f(program, location, 0f, 0f, 0f, 1f);
        else GL41.glProgramUniform4f(program, location, eq[0], eq[1], eq[2], eq[3]);
    }

    /** ShaderInstance.apply uploads Java Uniforms AFTER glUseProgram. Keep both in sync
     * before apply, or an initially dirty identity could overwrite the bind hook's cut. */
    public static void prepareShader(ShaderInstance shader) {
        IplSubLevelClipShader uniforms = (IplSubLevelClipShader) shader;
        set(uniforms.ipl$getSubLevelClipUniform(), equation(shader.getId(), false));
        set(uniforms.ipl$getSubLevelClipUniform2(), equation(shader.getId(), true));
    }

    private static void set(Uniform uniform, float[] eq) {
        if (uniform == null) return;
        if (eq == null) uniform.set(0f, 0f, 0f, 1f);
        else uniform.set(eq[0], eq[1], eq[2], eq[3]);
    }
}
