package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.q_misc_util.my_util.Plane;

/**
 * Writes our per-sub-level clip equation to the {@code ipl_subLevelClipEquation}
 * uniform that drives {@code gl_ClipDistance[1]} in the shader transformation.
 *
 * <p>This is independent of IP's {@code iportal_ClippingEquation /
 * gl_ClipDistance[0]} pipeline. We compute the equation directly from the
 * supplied {@link Plane} (sub-level-center-oriented, mirror-flip-applied --
 * see {@link SourceClipPortalFinder}) and the current camera position, in
 * the same form IP uses for its own inner clipping:
 *
 * <pre>
 *   c = -n · (planePoint - cameraPos)
 *   equation = (n.x, n.y, n.z, c)
 * </pre>
 *
 * <p>Then we evaluate the discard in the shader against the post-modelview
 * camera-relative world position via the
 * {@code gbufferModelViewInverse × iris_ModelViewMat × (Position + ChunkOffset)}
 * chain in {@code shader_transformation.yaml}. {@code n · worldPos + c > 0}
 * is the kept half-space.
 *
 * <p>{@code patchForSubLevel} uploads when a program is active. If a renderer
 * has only selected a shader, the uniform remains dirty for its next apply;
 * calling {@code glUniform} before that bind is invalid.
 */
public final class SubLevelClipUniformPatcher {

    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ipl.portalDiagnostics");
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip-patch");
    private static volatile long lastReportNanos = 0L;

    /**
     * Diagnostic mode -- when {@code -Dipl.sable.clip.forceAll=true} is set on the
     * JVM cmdline, override the equation with {@code (0, 0, 0, -1)}: every vertex
     * evaluates to -1 → discarded. If sub-level geometry STILL renders with this
     * active, the uniform/upload pipeline isn't reaching the shader at all.
     */
    private static final boolean FORCE_CLIP_ALL =
        Boolean.getBoolean("ipl.sable.clip.forceAll");

    static {
        LOG.info("[IPL-CLIP-PATCH] static init: FORCE_CLIP_ALL={}", FORCE_CLIP_ALL);
    }

    /**
     * Currently-active sub-level equation in <b>camera-relative world space</b>.
     * Used by chunk shaders (iris+sodium {@code terrain_*}) which compute
     * {@code _ipl_worldPos = gbufferModelViewInverse * iris_ModelViewMat *
     * (iris_Position + iris_ChunkOffset)} and dot against this equation in
     * world space.
     */
    private static volatile float[] currentSubLevelEqWorld = null;

    /**
     * Equation for vanilla Sable chunk vertices. Their {@code Position + ChunkOffset}
     * is plot-local after camera translation, unlike Iris/Sodium terrain world positions.
     */
    private static volatile float[] currentSubLevelEqVanillaInput = null;

    /**
     * Currently-active sub-level equation in <b>eye space</b> (rotated by the
     * camera's view rotation). Used by entity-style shaders (Iris-rewritten
     * {@code block_entity_diffuse} / {@code moving_block}, vanilla
     * {@code rendertype_entity_*}, Veil-managed Simulated/Aeronautics
     * shaders) which compute {@code _ipl_eyePos = iris_ModelViewMat *
     * iris_Position} (or {@code ModelViewMat * Position}) and dot against
     * this equation in eye space.
     *
     * <p><b>Why two variants:</b> the world-space equation has the form
     * {@code (n_w, c)} where {@code c = -n_w · (planePoint - camPos)}.
     * For an eye-space vertex {@code e = R_view · (P_world - camPos)},
     * the signed distance is {@code (R_view · n_w) · e + c}. So the
     * eye-space normal is the world normal rotated by the camera-only
     * view rotation; the constant stays the same.
     *
     * <p>Without this variant, eye-space shaders dot a rotated vertex
     * against an un-rotated equation -- the clip plane ends up at a
     * rotated angle, so cogs and other entity-style sub-level meshes
     * either always pass the clip test or get culled on a wrong-axis
     * plane (RenderDoc confirmed via EID 2294 in cog_leak5.rdc).
     */
    private static volatile float[] currentSubLevelEqEye = null;

    /**
     * @deprecated Use {@link #getCurrentSubLevelEqWorld()} or
     *     {@link #getCurrentSubLevelEqEye()} -- {@code IplGlUseProgramProbeMixin}
     *     picks the right space per program via {@code IplProgramRegistry.isEntityStyleProgram}.
     */
    @Deprecated
    public static float[] getCurrentSubLevelEq() {
        return currentSubLevelEqWorld;
    }

    /** Camera-relative world-space equation, or null if no bracket is active. */
    public static float[] getCurrentSubLevelEqWorld() {
        return currentSubLevelEqWorld;
    }

    /** Eye-space equation (world normal rotated by view), or null. */
    public static float[] getCurrentSubLevelEqEye() {
        return currentSubLevelEqEye;
    }

    public static float[] getCurrentSubLevelEqVanillaInput() {
        return currentSubLevelEqVanillaInput;
    }

    /**
     * Second cut plane (multi-straddle), same spaces as the primaries; null means
     * "no second cut" and uploaders write the neutral {@code (0,0,0,1)}. The shader
     * writes the two distances into independent slots 1 and 2.
     */
    private static volatile float[] currentSubLevelEqWorld2 = null;
    private static volatile float[] currentSubLevelEqEye2 = null;
    private static volatile float[] currentSubLevelEqVanillaInput2 = null;

    public static float[] getCurrentSubLevelEqWorld2() {
        return currentSubLevelEqWorld2;
    }

    public static float[] getCurrentSubLevelEqEye2() {
        return currentSubLevelEqEye2;
    }

    public static float[] getCurrentSubLevelEqVanillaInput2() {
        return currentSubLevelEqVanillaInput2;
    }

    // Slot-0 adaptation is scoped independently from the source-half cuts.
    // Shader.apply and raw binds must not replace this plot-local equation
    // with a camera-world equation during a Sable chunk draw.
    private static int portalInputProgram = -1;
    private static double[] portalInputEquation;

    public static double[] portalInputEquation(int program) {
        return program == portalInputProgram ? portalInputEquation : null;
    }

    private SubLevelClipUniformPatcher() {}

    /**
     * Compute the clip equation from {@code plane} (in camera-relative form) and
     * push to {@code ipl_subLevelClipEquation} on the currently-bound shader.
     * Upload only when its GL program is active; otherwise leave it dirty for
     * ShaderInstance.apply rather than issuing glUniform with program zero.
     */
    public static void patchForSubLevel(ClientSubLevel sub, Plane plane) {
        patchForSubLevel(sub, plane, null);
    }

    /**
     * Two-plane variant (multi-straddle): {@code plane2} is the SECOND cut, or null
     * for single-cut behavior. Each uses its own {@code gl_ClipDistance} slot (1 and 2).
     */
    public static void patchForSubLevel(
        ClientSubLevel sub, Plane plane, @org.jetbrains.annotations.Nullable Plane plane2
    ) {
        ShaderInstance shader = RenderSystem.getShader();
        // A BE scope may start with no matching terrain shader. Publish the cut anyway;
        // the actual program is updated now or when it subsequently binds.
        // Build the camera-relative world-space clip equation used by Iris/Sodium.
        // Vanilla Sable terrain receives an inverse-rotated normal below because its
        // Position + ChunkOffset remains plot-local until the model-view transform.
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        Vec3 n = plane.normal();
        Vec3 p = plane.pos();
        double c = -(n.x * (p.x - cameraPos.x)
                   + n.y * (p.y - cameraPos.y)
                   + n.z * (p.z - cameraPos.z));

        float nx, ny, nz, cw;
        if (FORCE_CLIP_ALL) {
            nx = 0f; ny = 0f; nz = 0f; cw = -1f;
        } else {
            nx = (float) n.x;
            ny = (float) n.y;
            nz = (float) n.z;
            cw = (float) c;
        }

        float[] eqVanillaInput = null;
        if (sub != null) {
            try {
                Pose3dc renderPose = sub.renderPose();
                Vec3 nInput = renderPose.transformNormalInverse(n);
                eqVanillaInput = new float[]{(float) nInput.x, (float) nInput.y, (float) nInput.z, cw};
            } catch (Exception e) {
                // Fall back to world space if a transient pose read is unavailable.
            }
        }
        currentSubLevelEqVanillaInput = eqVanillaInput;

        // Second cut (multi-straddle): same three spaces, from plane2's geometry.
        float[] eqWorld2 = null;
        float[] eqVanillaInput2 = null;
        if (plane2 != null) {
            Vec3 n2 = plane2.normal();
            Vec3 p2 = plane2.pos();
            double c2 = -(n2.x * (p2.x - cameraPos.x)
                        + n2.y * (p2.y - cameraPos.y)
                        + n2.z * (p2.z - cameraPos.z));
            eqWorld2 = new float[]{(float) n2.x, (float) n2.y, (float) n2.z, (float) c2};
            if (sub != null) {
                try {
                    Vec3 nInput2 = sub.renderPose().transformNormalInverse(n2);
                    eqVanillaInput2 = new float[]{
                        (float) nInput2.x, (float) nInput2.y, (float) nInput2.z, (float) c2};
                } catch (Exception e) {
                    // world fallback below
                }
            }
        }
        currentSubLevelEqWorld2 = eqWorld2;
        currentSubLevelEqVanillaInput2 = eqVanillaInput2;

        // Publish world-space equation for chunk shaders (iris+sodium
        // terrain_*) that dot against a world-space position chain.
        float[] eqWorld = new float[]{nx, ny, nz, cw};
        currentSubLevelEqWorld = eqWorld;

        // Compute and publish the eye-space variant for entity-style
        // shaders (Iris-rewritten block_entity_diffuse / moving_block,
        // vanilla rendertype_entity_*, Veil-managed Simulated/Aeronautics).
        // n_eye = R_view * n_world, c stays the same.
        //
        // The active ModelView can include portal frame transforms in addition to
        // camera rotation. Transform the plane covariantly through that exact
        // matrix; camera.rotation() alone is wrong in portal eye-space.
        float[] eqEye = transformEquationForModelView(eqWorld, RenderSystem.getModelViewMatrix());
        currentSubLevelEqEye = eqEye;
        if (eqWorld2 != null) {
            currentSubLevelEqEye2 = transformEquationForModelView(
                eqWorld2, RenderSystem.getModelViewMatrix());
        } else {
            currentSubLevelEqEye2 = null;
        }

        if (shader != null) IplProgramBindHook.prepareShader(shader);
        // Raw Flywheel/Iris binds can bypass RenderSystem's tracked shader. Query once
        // per actual scope, and write only that GL program (DSA, safe even for program 0).
        IplProgramBindHook.uploadSubLevel(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));

        // Defensive re-enable: Veil bloom (RenderDoc EID ~2453 in cog_leak3)
        // disables GL_CLIP_DISTANCE1 mid-frame and doesn't restore it. If our
        // bracket comes after bloom, slot-1 writes from the vertex shader are
        // silently ignored unless we explicitly re-enable.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        // Slot 2 = second sub-level cut, which now writes its own clip
        // distance rather than being min()'d into slot 1.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE2);

        long now = System.nanoTime();
        if (DIAGNOSTICS && shader != null && now - lastReportNanos >= 5_000_000_000L) {
            lastReportNanos = now;
            LOG.info("[IPL-CLIP-PATCH] shader={} forceAll={} subLevel={} wrote=({},{},{},{})",
                shader.getName(),
                FORCE_CLIP_ALL,
                sub != null ? sub.getUniqueId() : null,
                nx, ny, nz, cw
            );
        }
    }

    /** Converts a camera-relative world plane to the current shader eye space. */
    private static float[] transformEquationForModelView(float[] equation, Matrix4f modelView) {
        org.joml.Vector4f transformed = new org.joml.Vector4f(
            equation[0], equation[1], equation[2], equation[3]);
        new Matrix4f(modelView).invert().transpose().transform(transformed);
        return new float[]{transformed.x, transformed.y, transformed.z, transformed.w};
    }

    /**
     * Adapts IP's slot-0 portal equation for Sable's vanilla chunk draw. Sable
     * feeds {@code Position + ChunkOffset} in plot-local, camera-relative space;
     * its model-view matrix applies the sub-level rotation only afterwards. IP's
     * normal world-space terrain equation therefore has to be inverse-rotated for
     * this one draw. The constant is already camera-relative and remains valid.
     */
    public static boolean patchPortalClipForVanillaSubLevel(
        ShaderInstance shader, ClientSubLevel sub, double[] worldEquation
    ) {
        if (shader == null || sub == null || worldEquation == null) return false;

        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return false;

        try {
            Vec3 normal = new Vec3(worldEquation[0], worldEquation[1], worldEquation[2]);
            Vec3 localNormal = sub.renderPose().transformNormalInverse(normal);
            // IPL fix (micro rim behind the portal plane).
            //
            // MixinLevelRenderer installs IP's slot-0 inner clipping with
            // `adjustment = -FrontClipping.ADJUSTMENT`, i.e. the plane is pushed 0.01
            // blocks BACK along its own normal so IP's own destination TERRAIN does not
            // z-fight with the portal quad. The kept half-space therefore starts 0.01
            // blocks BEFORE the mathematical portal plane.
            //
            // Our slot-1 sub-level cut sits exactly ON the mathematical plane and keeps
            // the complementary half. Both slots are active for a sub-level draw inside
            // a portal-through pass, so the surviving region is the INTERSECTION:
            //
            //     n·p > w_exact          (slot 0, shifted back by 0.01)
            //     n·p < w_exact + 0.01   (slot 1, exact)
            //
            // = a 0.01-block-thick slab of the ship sitting just past the portal plane.
            // That slab is the "micro rim": it is drawn for the whole crossing and its
            // width never changes, because it is literally ADJUSTMENT wide.
            // (RenderDoc obodok.rdc: slot0 = {0.72907,-0.1988,0.65493,-0.84243},
            //  slot1 = {-0.72907,0.1988,-0.65493,0.85243} -> |Δw| = 0.01 exactly.)
            //
            // Undoing the adjustment for THIS scoped sub-level draw only puts both cuts
            // on the identical plane, so the intersection is empty and the rim is gone.
            // IP's z-fighting guard is unaffected: slot-0 is restored to its original
            // world equation by restorePortalClip() as soon as the bracket exits, so
            // the parent world's terrain still renders with the 0.01 back-shift.
            double exactW = worldEquation[3] - qouteall.imm_ptl.core.render.FrontClipping.ADJUSTMENT;
            portalInputProgram = shader.getId();
            portalInputEquation = new double[]{localNormal.x, localNormal.y, localNormal.z, exactW};
            uniform.set(
                (float) localNormal.x, (float) localNormal.y, (float) localNormal.z,
                (float) exactW
            );
            uniform.upload();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Restore IP's world-space slot-0 equation after the scoped Sable draw. */
    public static void restorePortalClip(ShaderInstance shader, double[] worldEquation) {
        portalInputProgram = -1;
        portalInputEquation = null;
        if (shader == null || worldEquation == null) return;

        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return;
        uniform.set(
            (float) worldEquation[0], (float) worldEquation[1],
            (float) worldEquation[2], (float) worldEquation[3]
        );
        uniform.upload();
    }

    /** Snapshot references are immutable equations; nested consumers must restore all spaces. */
    public record State(float[] world, float[] eye, float[] input,
                        float[] world2, float[] eye2, float[] input2) {}

    public static State captureState() {
        return new State(currentSubLevelEqWorld, currentSubLevelEqEye, currentSubLevelEqVanillaInput,
            currentSubLevelEqWorld2, currentSubLevelEqEye2, currentSubLevelEqVanillaInput2);
    }

    public static void restoreState(State state) {
        currentSubLevelEqWorld = state.world();
        currentSubLevelEqEye = state.eye();
        currentSubLevelEqVanillaInput = state.input();
        currentSubLevelEqWorld2 = state.world2();
        currentSubLevelEqEye2 = state.eye2();
        currentSubLevelEqVanillaInput2 = state.input2();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) IplProgramBindHook.prepareShader(shader);
        IplProgramBindHook.uploadSubLevel(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }

    /**
     * Restore the per-sub-level clip uniform to "no clipping" {@code (0,0,0,1)} and
     * push to the GPU. Used by mixin RETURN paths so subsequent draws (vanilla
     * terrain after our sub-level render) don't inherit a stale equation.
     */
    public static void clearAndUpload() {
        // Clear the published equations first so subsequent program binds
        // outside the sub-level bracket fall back to portal-mirror or
        // no-clip writes in IplGlUseProgramProbeMixin.
        currentSubLevelEqWorld = null;
        currentSubLevelEqEye = null;
        currentSubLevelEqVanillaInput = null;
        currentSubLevelEqWorld2 = null;
        currentSubLevelEqEye2 = null;
        currentSubLevelEqVanillaInput2 = null;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) IplProgramBindHook.prepareShader(shader);
        IplProgramBindHook.uploadSubLevel(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }
}
