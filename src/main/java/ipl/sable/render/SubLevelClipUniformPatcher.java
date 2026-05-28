package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.CHelper;
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
 * <p>{@code patchForSubLevel} also calls {@code upload()} explicitly because
 * Sable's render path doesn't re-apply the shader after our HEAD mixin runs --
 * setting the uniform without uploading would leave the GPU value stale.
 */
public final class SubLevelClipUniformPatcher {

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

    // Per-reason last-log-time. With multiple distinct shader names alternating
    // every frame (shadow_terrain_cutout, shadow_translucent, empty-name, etc.)
    // a naive "suppress consecutive same reason" approach would fire on every
    // call. Use a map so each distinct reason logs at most once per 5s.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> earlyReturnLastLogNanos =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static void logEarlyReturn(String reason) {
        long now = System.nanoTime();
        Long last = earlyReturnLastLogNanos.get(reason);
        if (last != null && now - last < 5_000_000_000L) {
            return;
        }
        earlyReturnLastLogNanos.put(reason, now);
        LOG.warn("[IPL-CLIP-PATCH] early return: {}", reason);
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
     * Persistent "last seen" world-space equation, never cleared by
     * {@link #clearAndUpload}. Set whenever {@link #patchForSubLevel}
     * fires. Used by hooks that run OUTSIDE the sub-level bracket --
     * notably {@code IplFlywheelEmbeddedClipMixin.setupDraw} on Flywheel's
     * {@code EmbeddedEnvironment}, which runs at a separate frame stage
     * after all Sable brackets have exited (the cog draws happen here
     * because Flywheel batches per-sub-level BE visualizations and
     * dispatches them outside Sable's chunk-render brackets).
     *
     * <p>Caveat: for multi-sub-level scenes, this only holds the LAST
     * sub-level's equation -- not per-sub-level. Adequate for the airship
     * test case; needs a sceneId-indexed map for full correctness.
     */
    private static volatile float[] latestSubLevelEqWorld = null;

    /** Persistent counterpart of {@link #currentSubLevelEqEye}. */
    private static volatile float[] latestSubLevelEqEye = null;

    /**
     * Timestamp (System.nanoTime) of the most recent {@link #patchForSubLevel}
     * call. Latest equations are considered "fresh" if set within
     * {@link #LATEST_STALE_THRESHOLD_NANOS}; older = treated as null by
     * {@link #getLatestSubLevelEqWorld} / {@code Eye} / {@code Local}.
     *
     * <p>Without this, latest values persist forever after a sub-level
     * disengages from a portal -- and the Flywheel embedded clip mixin
     * keeps writing them + enabling CD1 every frame. Result: "clipping
     * enabled before contact" -- the system stays active long after the
     * airship has left the portal. Fresh-frame check fixes this without
     * needing a per-frame hook.
     *
     * <p>50ms ≈ 3 frames at 60fps; long enough that mid-frame Flywheel
     * draws see the latest from the bracket that fired earlier in the
     * same frame, short enough to expire within a few frames after the
     * sub-level disengages.
     */
    private static volatile long latestSetNanos = 0L;
    private static final long LATEST_STALE_THRESHOLD_NANOS = 50_000_000L; // 50ms

    /**
     * Sub-level-local equation. For programs whose vertex shader chain
     * produces a position in the SUB-LEVEL's local coordinate frame
     * (not world / not camera-relative).
     *
     * <p>Empirical finding (user-confirmed by airship Y-rotation test):
     * for Create cog block-entity rendering, the chain
     * {@code gbufferModelViewInverse * iris_ModelViewMat * iris_Position}
     * produces a SUB-LEVEL-LOCAL position rather than world position --
     * apparently Sable's sub-level transform is NOT in iris_ModelViewMat
     * for the BE path even though it IS for the chunked-section path.
     * Dotting a world-space equation against sub-level-local position
     * gives clipping that rotates with the airship (works when airship
     * rotation aligns sub-level axes with world axes; wrong otherwise).
     *
     * <p>Math: world plane (n_w, c_w) with c_w = -n_w·p_w. Substitute
     * P_w = R_sub·P_local + t_sub:
     *   dot(R_sub·P_local + t_sub, n_w) - n_w·p_w
     *   = dot(P_local, R_sub^T·n_w) + n_w·(t_sub - p_w)
     * Local form: n_local = R_sub^T·n_w, c_local = -n_w·(p_w - t_sub).
     * Pose3dc gives this directly via transformNormalInverse +
     * transformPositionInverse.
     */
    private static volatile float[] currentSubLevelEqLocal = null;

    /** Persistent counterpart of {@link #currentSubLevelEqLocal}. */
    private static volatile float[] latestSubLevelEqLocal = null;

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

    /**
     * World-space equation from the most recent {@link #patchForSubLevel},
     * persistent across bracket exit BUT returns null if no patch fired
     * within {@link #LATEST_STALE_THRESHOLD_NANOS}. Used by hooks that
     * run outside the Sable bracket scope (Flywheel EmbeddedEnvironment
     * setupDraw) which can fire even when no bracket is active for the
     * sub-level being drawn -- the freshness check prevents stale-value
     * application after the airship disengages.
     */
    public static float[] getLatestSubLevelEqWorld() {
        if (ipl$latestIsStale()) return null;
        return latestSubLevelEqWorld;
    }

    /** Eye-space counterpart of {@link #getLatestSubLevelEqWorld()}. */
    public static float[] getLatestSubLevelEqEye() {
        if (ipl$latestIsStale()) return null;
        return latestSubLevelEqEye;
    }

    /** Sub-level-local equation, current bracket. */
    public static float[] getCurrentSubLevelEqLocal() {
        return currentSubLevelEqLocal;
    }

    /** Sub-level-local equation, persistent across bracket exit (with freshness check). */
    public static float[] getLatestSubLevelEqLocal() {
        if (ipl$latestIsStale()) return null;
        return latestSubLevelEqLocal;
    }

    private static boolean ipl$latestIsStale() {
        return System.nanoTime() - latestSetNanos > LATEST_STALE_THRESHOLD_NANOS;
    }

    private SubLevelClipUniformPatcher() {}

    /**
     * Compute the clip equation from {@code plane} (in camera-relative form) and
     * push to {@code ipl_subLevelClipEquation} on the currently-bound shader.
     * Also calls {@code upload()} so the GPU sees the new value before the next
     * draw call -- Sable's render path doesn't re-apply the shader between our
     * HEAD inject and its draw, so a bare {@code set()} would never propagate.
     */
    public static void patchForSubLevel(ClientSubLevel sub, Plane plane) {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            logEarlyReturn("RenderSystem.getShader() null");
            return;
        }
        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        if (uniform == null) {
            // Silent skip for shaders we haven't transformed: shadow shaders, empty
            // name (Veil/Sodium internal blits), particle, vanilla rendertype_*.
            // They fire every frame; logging would spam.
            String name = shader.getName();
            if (!name.isEmpty()
                && !name.startsWith("shadow_")
                && !name.equals("particle")
                && !name.startsWith("rendertype_")) {
                logEarlyReturn("shader '" + name + "' has no ipl_subLevelClipEquation uniform");
            }
            return;
        }

        // Build the camera-relative world-space clip equation. The shader's discard
        // tests against (gbufferModelViewInverse * iris_ModelViewMat * (Position +
        // ChunkOffset)).xyz which is camera-relative world coords; our (n, c) is in
        // the same space, so dot product + c > 0 ⇔ kept side.
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

        uniform.set(nx, ny, nz, cw);
        uniform.upload();

        // Publish world-space equation for chunk shaders (iris+sodium
        // terrain_*) that dot against a world-space position chain.
        float[] eqWorld = new float[]{nx, ny, nz, cw};
        currentSubLevelEqWorld = eqWorld;
        latestSubLevelEqWorld = eqWorld;
        // Mark freshness so getLatestSubLevelEq* don't return stale values
        // after the sub-level disengages from any portal.
        latestSetNanos = System.nanoTime();

        // Compute and publish the eye-space variant for entity-style
        // shaders (Iris-rewritten block_entity_diffuse / moving_block,
        // vanilla rendertype_entity_*, Veil-managed Simulated/Aeronautics).
        // n_eye = R_view * n_world, c stays the same.
        //
        // Mojang's Camera.rotation() is the camera-to-world rotation; the
        // world-to-eye (view) rotation is its conjugate (inverse for unit
        // quaternions). Apply that to the world normal.
        Quaternionf camToWorld = Minecraft.getInstance().gameRenderer
            .getMainCamera().rotation();
        Quaternionf worldToView = new Quaternionf(camToWorld).conjugate();
        Vector3f nEye = worldToView.transform(new Vector3f(nx, ny, nz));
        float[] eqEye = new float[]{nEye.x, nEye.y, nEye.z, cw};
        currentSubLevelEqEye = eqEye;
        latestSubLevelEqEye = eqEye;

        // Compute sub-level-LOCAL form. Cog / per-BE shaders under Sable
        // apparently produce sub-level-local position from the
        // `gbufferModelViewInverse * iris_ModelViewMat * iris_Position`
        // chain (iris_ModelViewMat for BE path doesn't include the
        // sub-level transform that's present in the chunk-section path).
        // We need a matching equation form so the dot product makes sense.
        float[] eqLocal = null;
        if (sub != null) {
            try {
                Pose3dc renderPose = sub.renderPose();
                Vec3 nLocal = renderPose.transformNormalInverse(n);
                Vec3 pLocal = renderPose.transformPositionInverse(p);
                double cLocal = -(nLocal.x * pLocal.x
                                + nLocal.y * pLocal.y
                                + nLocal.z * pLocal.z);
                eqLocal = new float[]{
                    (float) nLocal.x, (float) nLocal.y, (float) nLocal.z,
                    (float) cLocal
                };
            } catch (Exception e) {
                // Pose unavailable / inverse failed -- leave local null,
                // entity-style programs fall back to world.
            }
        }
        currentSubLevelEqLocal = eqLocal;
        latestSubLevelEqLocal = eqLocal;

        // Spray BOTH variants to every program known to carry the uniform,
        // picking the right space per program via the entity-style registry.
        // Handles the case (RenderDoc-confirmed in cog_leak5.rdc EID 2294)
        // where a program was bound BEFORE this bracket started and won't
        // re-trigger _glUseProgram during the bracket -- without this loop
        // its slot-1 stays at (0,0,0,1) and the cog leaks.
        // glProgramUniform4f writes to a specified program regardless of
        // bind state (GL 4.1+).
        final float[] eqW = currentSubLevelEqWorld;
        final float[] eqL = currentSubLevelEqLocal;
        IplSubLevelUniformRegistry.forEach((programId, loc) -> {
            // Entity-style programs (block_entity_diffuse, moving_block,
            // particles, etc.) under Sable's per-BE path produce
            // sub-level-LOCAL position from the recovery chain -- write
            // the local equation form so the dot matches. Fall back to
            // world if local wasn't computable (no sub-level pose).
            // Chunks (terrain_*) produce world position from their chain
            // (with iris_ChunkOffset) and keep the world equation.
            float[] eq;
            if (IplProgramRegistry.isEntityStyleProgram(programId) && eqL != null) {
                eq = eqL;
            } else {
                eq = eqW;
            }
            GL41.glProgramUniform4f(programId, loc, eq[0], eq[1], eq[2], eq[3]);
        });

        // Defensive re-enable: Veil bloom (RenderDoc EID ~2453 in cog_leak3)
        // disables GL_CLIP_DISTANCE1 mid-frame and doesn't restore it. If our
        // bracket comes after bloom, slot-1 writes from the vertex shader are
        // silently ignored unless we explicitly re-enable.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);

        long now = System.nanoTime();
        if (now - lastReportNanos >= 5_000_000_000L) {
            lastReportNanos = now;
            LOG.info("[IPL-CLIP-PATCH] shader={} forceAll={} subLevel={} wrote=({},{},{},{})",
                shader.getName(),
                FORCE_CLIP_ALL,
                sub != null ? sub.getUniqueId() : null,
                nx, ny, nz, cw
            );
        }
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

        // NOTE: We intentionally do NOT spray (0,0,0,1) to all registered
        // programs here. Doing so was the root cause of the cog-leak bug
        // (cog_leak3/5.rdc EID 2294: slot-1 was at default because clearAndUpload
        // had just reset every program after the chunk bracket exited).
        // The bracket's GL_CLIP_DISTANCE1 disable in finally is what makes
        // stale slot-1 values inert for subsequent draws -- so leaving
        // them at the last sprayed equation is safe.
        //
        // For the currently-bound shader, the existing set/upload still
        // restores its slot-1 to identity, in case some downstream code
        // re-enables CLIP_DISTANCE1 without going through patchForSubLevel.

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        if (uniform == null) return;
        uniform.set(0f, 0f, 0f, 1f);
        uniform.upload();
    }
}
