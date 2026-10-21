package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import java.util.HashMap;
import java.util.Map;

/** Render-thread upload to the program actually used by a draw, never a registry broadcast. */
public final class IplProgramBindHook {
    private static final Map<Integer, int[]> LOCATIONS = new HashMap<>();
    private static boolean latePortalClipEnabled;
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
        if (program <= 0 || !IPGlobal.enableClippingMechanism) return;
        boolean portalClip = FrontClipping.isClippingEnabled;
        boolean portalPass = PortalRendering.isRendering();
        boolean subClip = SubLevelClipUniformPatcher.getCurrentSubLevelEqWorld() != null;
        if (!portalClip && !portalPass && latePortalClipEnabled) {
            // A late bind can re-enable slot 0 while FrontClipping's flag is
            // false. FrontClipping.disableClipping() cannot clear that raw GL
            // state; explicitly release it on return to the root pass.
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
            latePortalClipEnabled = false;
        }
        if (!portalClip && !portalPass && !subClip) return;
        int[] locs = locations(program);
        if (portalClip) IplClipEquationCache.refreshFromActive();
        if (portalClip || portalPass) {
            GL11.glEnable(GL11.GL_CLIP_PLANE0);
            if (!portalClip) latePortalClipEnabled = true;
        }
        if (locs[0] >= 0 && (portalClip || portalPass)) {
            double[] eq = portalEquation(program);
            if (eq != null) GL41.glProgramUniform4f(program, locs[0],
                (float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
            else GL41.glProgramUniform4f(program, locs[0], 0f, 0f, 0f, 1f);
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
        if (!IPGlobal.enableClippingMechanism) return;
        // apply()/ExtendedShader.updateUniforms upload the Java objects AFTER the
        // raw bind hook. Synchronize slot 0 too, or a dirty neutral Java Uniform
        // overwrites the correct DSA upload just before the draw.
        Uniform portal = ((IEShader) shader).ip_getClippingEquationUniform();
        if (portal != null) {
            double[] eq = portalEquation(shader.getId());
            if (eq == null) portal.set(0f, 0f, 0f, 1f);
            else portal.set((float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
        }
        IplSubLevelClipShader uniforms = (IplSubLevelClipShader) shader;
        set(uniforms.ipl$getSubLevelClipUniform(), equation(shader.getId(), false));
        set(uniforms.ipl$getSubLevelClipUniform2(), equation(shader.getId(), true));
    }

    private static double[] portalEquation(int program) {
        double[] scoped = SubLevelClipUniformPatcher.portalInputEquation(program);
        if (scoped != null) return scoped;
        boolean eye = IplProgramRegistry.isEntityStyleProgram(program);
        if (FrontClipping.isClippingEnabled) {
            return eye ? FrontClipping.getActiveClipPlaneEquationAfterModelView()
                : FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        }
        return eye ? IplClipEquationCache.getEyeEq() : IplClipEquationCache.getWorldEq();
    }

    private static void set(Uniform uniform, float[] eq) {
        if (uniform == null) return;
        if (eq == null) uniform.set(0f, 0f, 0f, 1f);
        else uniform.set(eq[0], eq[1], eq[2], eq[3]);
    }
}
