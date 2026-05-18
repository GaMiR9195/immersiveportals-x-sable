package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
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
     * Currently-active sub-level equation, mirrored as a (nx, ny, nz, w) float
     * array. Set by {@link #patchForSubLevel} when a sub-level bracket starts
     * and cleared by {@link #clearAndUpload} when it ends.
     *
     * <p>Read by {@code IplGlUseProgramProbeMixin} so that any shader bound
     * during the sub-level body (cog moving_block, particle particles, etc.)
     * also receives the per-sub-level equation on its
     * {@code ipl_subLevelClipEquation} -- not the portal-clip-mirror equation
     * we'd otherwise write for slot 1. Without this, the original
     * {@code patchForSubLevel} write only reaches the shader that happens to
     * be bound at the moment {@code ipl$withClip} runs (typically the chunked
     * terrain shader); subsequent binds inside {@code body.run()} would
     * silently get a stale or wrong-plane equation.
     *
     * <p>Static volatile rather than ThreadLocal because all GL state changes
     * happen on the render thread; a single field is simpler and faster.
     */
    private static volatile float[] currentSubLevelEq = null;

    /**
     * Read the currently-active sub-level equation as a 4-float array, or
     * {@code null} if no sub-level bracket is active. The
     * {@link ipl.sable.mixin.client.IplGlUseProgramProbeMixin} consults this
     * on every program bind so per-sub-level slot-1 propagates to all shaders
     * bound during the sub-level render, not just the first one.
     */
    public static float[] getCurrentSubLevelEq() {
        return currentSubLevelEq;
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

        // Publish for subsequent program binds inside the same sub-level
        // bracket -- IplGlUseProgramProbeMixin reads this on _glUseProgram
        // HEAD so cog / particle / animated-BE shaders bound after the
        // initial shader inherit the same sub-level equation. Without this,
        // they'd silently fall back to the portal-clip mirror, producing
        // the floating-sub-component bug.
        currentSubLevelEq = new float[]{nx, ny, nz, cw};

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
        // Clear the published equation first so subsequent program binds
        // outside the sub-level bracket fall back to portal-mirror or
        // no-clip writes in IplGlUseProgramProbeMixin.
        currentSubLevelEq = null;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        if (uniform == null) return;
        uniform.set(0f, 0f, 0f, 1f);
        uniform.upload();
    }
}
