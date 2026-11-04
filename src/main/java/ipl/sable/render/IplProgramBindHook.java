package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.HashMap;
import java.util.Map;

/** Render-thread upload to the program actually used by a draw, never a registry broadcast. */
public final class IplProgramBindHook {
    /** Per program: {@code [0]} IP's slot-0 equation, {@code [1 + i]} sub-level slot {@code i}. */
    private static final Map<Integer, int[]> LOCATIONS = new HashMap<>();
    private IplProgramBindHook() {}

    /**
     * GL name of element {@code slot} of {@code ipl_subLevelClipEquation[SLOTS]}. Element 0 is
     * queried by the bare array name, which also finds it in a program that declares a
     * single {@code vec4}.
     */
    public static String subLevelUniformName(int slot) {
        return slot == 0 ? "ipl_subLevelClipEquation" : "ipl_subLevelClipEquation[" + slot + "]";
    }

    private static int[] locations(int program) {
        return LOCATIONS.computeIfAbsent(program, p -> {
            int[] locs = new int[1 + IplClipUniformPatcher.SLOTS];
            locs[0] = GL20.glGetUniformLocation(p, "iportal_ClippingEquation");
            for (int i = 0; i < IplClipUniformPatcher.SLOTS; i++) {
                locs[1 + i] = GL20.glGetUniformLocation(p, subLevelUniformName(i));
            }
            return locs;
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
        boolean subClip = IplClipUniformPatcher.hasSubLevelClip();
        if (!portalClip && !portalPass) {
            // Вне портального прохода и без активного FrontClipping слот 0 не нужен никому, а
            // включённым его оставляет и поздний bind, и сам IP: MixinRenderSystem_Clipping
            // включает GL_CLIP_PLANE0, не поднимая флаг FrontClipping, и disableClipping() это
            // состояние не гасит. Забытый слот со старым уравнением отсекал бы существ и BE
            // возле портала в зависимости от положения камеры, поэтому он гасится всегда.
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
        }
        if (!portalClip && !portalPass && !subClip) return;
        int[] locs = locations(program);
        if (portalClip) IplClipEquationCache.refreshFromActive();
        if (portalClip || portalPass) GL11.glEnable(GL11.GL_CLIP_PLANE0);
        if (locs[0] >= 0 && (portalClip || portalPass)) {
            double[] eq = portalEquation(program);
            if (eq != null) GL41.glProgramUniform4f(program, locs[0],
                (float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
            else GL41.glProgramUniform4f(program, locs[0], 0f, 0f, 0f, 1f);
        }
        if (subClip) {
            uploadSubLevel(program);
            // Re-enables what Veil bloom may have switched off; the bracket exit turns all
            // sub-level slots off again (IplClipUniformPatcher#disableSlots).
            for (int i = 0; i < IplClipUniformPatcher.SLOTS; i++) {
                if (locs[1 + i] >= 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + 1 + i);
            }
        }
    }

    private static float[] equation(int program, int slot) {
        // Plot-local («чанковое») уравнение — только внутри чанковой скобки Sable; см.
        // IplClipUniformPatcher#chunkInputSpace. BER на тех же программах — мировое.
        if (IplClipUniformPatcher.isChunkInputSpace()
            && IplProgramRegistry.usesVanillaSubLevelInputSpace(program)) {
            return IplClipUniformPatcher.getCurrentSubLevelEqVanillaInput(slot);
        }
        if (IplProgramRegistry.isEntityStyleProgram(program)) {
            return IplClipUniformPatcher.getCurrentSubLevelEqEye(slot);
        }
        return IplClipUniformPatcher.getCurrentSubLevelEqWorld(slot);
    }

    /** Covers an already-bound program at bracket entry, without touching other programs. */
    public static void uploadSubLevel(int program) {
        if (program <= 0) return;
        int[] locs = locations(program);
        for (int i = 0; i < IplClipUniformPatcher.SLOTS; i++) {
            write(program, locs[1 + i], equation(program, i));
        }
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
        for (int i = 0; i < IplClipUniformPatcher.SLOTS; i++) {
            set(uniforms.ipl$getSubLevelClipUniform(i), equation(shader.getId(), i));
        }
    }

    private static double[] portalEquation(int program) {
        double[] scoped = IplClipUniformPatcher.portalInputEquation(program);
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
