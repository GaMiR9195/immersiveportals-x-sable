package ipl.sable.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.q_misc_util.my_util.Plane;

import java.util.List;

/**
 * Writes the sub-level clip equations to the {@code ipl_subLevelClipEquation[SLOTS]} uniform
 * array that drives {@code gl_ClipDistance[1..SLOTS]} in the shader transformation: one
 * independent hardware slot per plane of the current piece's convex cell (see
 * {@link IplClipCells}).
 *
 * <p>This is independent of IP's {@code iportal_ClippingEquation /
 * gl_ClipDistance[0]} pipeline. We compute each equation directly from the
 * supplied {@link Plane} (see {@link IplClipPortalFinder}) and the current camera position, in
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
 * is the kept half-space; a vertex survives only inside all of them.
 */
public final class IplClipUniformPatcher {

    /**
     * Sub-level cut slots, {@code gl_ClipDistance[1..SLOTS]}: with IP's slot 0 that is 7 of the
     * 8 clip distances every GL 3.0 implementation guarantees. A prism has five faces, so one
     * cell of a piece near one portal always fits; deeper chains drop redundant planes first
     * (inside the plot box) and only then the last ones.
     */
    public static final int SLOTS = 6;

    private static final float[][] NONE = new float[SLOTS][];

    /**
     * Currently-active sub-level equations in <b>camera-relative world space</b>, per slot
     * (null = no cut in that slot, uploaded as the neutral {@code (0,0,0,1)}).
     * Used by chunk shaders (iris+sodium {@code terrain_*}) which compute
     * {@code _ipl_worldPos = gbufferModelViewInverse * iris_ModelViewMat *
     * (iris_Position + iris_ChunkOffset)} and dot against these equations in
     * world space.
     */
    private static volatile float[][] currentSubLevelEqWorld = NONE;

    /**
     * Equations for vanilla Sable chunk vertices. Their {@code Position + ChunkOffset}
     * is plot-local after camera translation, unlike Iris/Sodium terrain world positions.
     */
    private static volatile float[][] currentSubLevelEqVanillaInput = NONE;

    /**
     * Currently-active sub-level equations in <b>eye space</b> (rotated by the
     * camera's view rotation). Used by entity-style shaders (Iris-rewritten
     * {@code block_entity_diffuse} / {@code moving_block}, vanilla
     * {@code rendertype_entity_*}, Veil-managed Simulated/Aeronautics
     * shaders) which compute {@code _ipl_eyePos = iris_ModelViewMat *
     * iris_Position} (or {@code ModelViewMat * Position}) and dot against
     * these equations in eye space.
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
     * plane.
     */
    private static volatile float[][] currentSubLevelEqEye = NONE;

    /** Whether a sub-level bracket is active (its cell may still be empty: all slots neutral). */
    private static volatile boolean subLevelClip;

    public static boolean hasSubLevelClip() {
        return subLevelClip;
    }

    /** Camera-relative world-space equation of {@code slot}, or null. */
    @Nullable
    public static float[] getCurrentSubLevelEqWorld(int slot) {
        return currentSubLevelEqWorld[slot];
    }

    /** Eye-space equation of {@code slot} (world normal rotated by view), or null. */
    @Nullable
    public static float[] getCurrentSubLevelEqEye(int slot) {
        return currentSubLevelEqEye[slot];
    }

    @Nullable
    public static float[] getCurrentSubLevelEqVanillaInput(int slot) {
        return currentSubLevelEqVanillaInput[slot];
    }

    /**
     * Пространство входных вершин «как у чанков Sable» включено только внутри скобки
     * {@code ChunkedRenderDataMixin} вокруг {@code renderChunkedSubLevel}.
     *
     * <p>Флаг, а не имя шейдера: у чанковой отрисовки Sable {@code Position + ChunkOffset} —
     * plot-локальные координаты, и нормаль среза приходится переводить в этот вход
     * ({@link #chunkInputNormal}). Те же программы {@code rendertype_solid/cutout/translucent}
     * рисуют и BER (кинетика Create без Flywheel, верёвки Simulated через
     * {@code RenderType.solid()}), а у BER-вершин позиция уже мировая относительно камеры. Выбор
     * по имени программы срезал бы BER повернутого корабля по повернутой плоскости, поэтому вне
     * чанковой скобки эти программы получают мировое уравнение, как и всё остальное.
     */
    private static boolean chunkInputSpace;

    public static void beginChunkInputSpace() {
        chunkInputSpace = true;
    }

    public static void endChunkInputSpace() {
        chunkInputSpace = false;
    }

    public static boolean isChunkInputSpace() {
        return chunkInputSpace;
    }

    // Slot-0 adaptation is scoped independently from the source-half cuts.
    // Shader.apply and raw binds must not replace this plot-local equation
    // with a camera-world equation during a Sable chunk draw.
    private static int portalInputProgram = -1;
    private static double[] portalInputEquation;

    public static double[] portalInputEquation(int program) {
        return program == portalInputProgram ? portalInputEquation : null;
    }

    private IplClipUniformPatcher() {}

    /**
     * Publishes the cell {@code planes} (the first {@link #SLOTS}) in all three input spaces,
     * uploads them to the currently bound program and enables the slots that program writes.
     *
     * <p>Upload goes only to the active GL program; a shader that is merely selected keeps
     * the uniforms dirty for its next {@code apply}, because {@code glUniform} without a
     * bound program is invalid.
     */
    public static void patchForSubLevel(ClientSubLevel sub, List<Plane> planes) {
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        int count = Math.min(planes.size(), SLOTS);
        float[][] world = new float[SLOTS][];
        float[][] eye = new float[SLOTS][];
        float[][] input = new float[SLOTS][];
        Matrix4f eyeTransform = count == 0 ? null
            : new Matrix4f(RenderSystem.getModelViewMatrix()).invert().transpose();
        for (int i = 0; i < count; i++) {
            Plane plane = planes.get(i);
            world[i] = worldEquation(plane, cameraPos);
            eye[i] = transformEquation(world[i], eyeTransform);
            input[i] = vanillaInputEquation(sub, plane, world[i][3]);
        }
        currentSubLevelEqWorld = world;
        currentSubLevelEqEye = eye;
        currentSubLevelEqVanillaInput = input;
        subLevelClip = true;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) IplProgramBindHook.prepareShader(shader);
        // Raw Flywheel/Iris binds can bypass RenderSystem's tracked shader. Query once
        // per actual scope, and write only that GL program (DSA, safe even for program 0).
        IplProgramBindHook.uploadSubLevel(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));

        // Each cut owns its own slot: min() of two linear functions is only piecewise-linear
        // while the rasterizer interpolates every clip distance linearly, so a shared slot
        // would replace the crease between two planes with a straight chord. All slots are
        // enabled on every scope (a raw bind may follow without our hook, and Veil bloom
        // disables GL_CLIP_DISTANCE1 mid-frame without restoring it); unused slots carry the
        // neutral equation.
        for (int i = 1; i <= SLOTS; i++) GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + i);
    }

    /** Turns every sub-level slot off (bracket exit: the slots belong to the bracket only). */
    public static void disableSlots() {
        for (int i = 1; i <= SLOTS; i++) GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + i);
    }

    /** {@code (n, -n · (p - camera))}: the plane in camera-relative world space. */
    private static float[] worldEquation(Plane plane, Vec3 cameraPos) {
        Vec3 n = plane.normal();
        double c = -n.dot(plane.pos().subtract(cameraPos));
        return new float[]{(float) n.x, (float) n.y, (float) n.z, (float) c};
    }

    /**
     * The same plane for Sable's vanilla chunk input, where {@code Position + ChunkOffset}
     * stays plot-local until the model-view matrix: the normal is taken into that input by
     * {@link #chunkInputNormal}, the camera-relative constant stays valid.
     */
    private static float[] vanillaInputEquation(ClientSubLevel sub, Plane plane, float c) {
        Vector3d n = chunkInputNormal(sub, plane.normal());
        return new float[]{(float) n.x, (float) n.y, (float) n.z, c};
    }

    /**
     * A world plane normal in Sable's vanilla chunk input. The chunk model matrix is the
     * render pose's rotation and scale, {@code M = R·S} (Create: Pocket Sized scales it), so
     * {@code n · (world - camera) = (Mᵀn) · input} with {@code Mᵀn = S·R⁻¹n}. Not
     * {@code transformNormalInverse}: its {@code S⁻¹·R⁻¹n} moved the cut of a scaled ship by
     * {@code (1 - s²)} times the camera's distance to the plane, so a shrunk ship or its
     * kept half went missing depending on where the camera stood.
     */
    private static Vector3d chunkInputNormal(ClientSubLevel sub, Vec3 worldNormal) {
        Pose3dc pose = sub.renderPose();
        return pose.orientation()
            .transformInverse(new Vector3d(worldNormal.x, worldNormal.y, worldNormal.z))
            .mul(pose.scale());
    }

    /** Converts a camera-relative world plane to the current shader eye space ({@code M^-T}). */
    private static float[] transformEquation(float[] equation, Matrix4f inverseTranspose) {
        Vector4f transformed = new Vector4f(
            equation[0], equation[1], equation[2], equation[3]);
        inverseTranspose.transform(transformed);
        return new float[]{transformed.x, transformed.y, transformed.z, transformed.w};
    }

    /**
     * Adapts IP's slot-0 portal equation for Sable's vanilla chunk draw. Sable
     * feeds {@code Position + ChunkOffset} in plot-local, camera-relative space;
     * its model-view matrix applies the sub-level rotation and scale only afterwards.
     * IP's normal world-space terrain equation therefore has to be taken into that
     * input ({@link #chunkInputNormal}) for this one draw. The constant is already
     * camera-relative and remains valid.
     */
    public static boolean patchPortalClipForVanillaSubLevel(
        ShaderInstance shader, ClientSubLevel sub, double[] worldEquation
    ) {
        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return false;

        Vector3d localNormal = chunkInputNormal(
            sub, new Vec3(worldEquation[0], worldEquation[1], worldEquation[2]));
        // MixinLevelRenderer installs IP's slot-0 inner clipping pushed 0.01 blocks back along
        // its normal (adjustment = -FrontClipping.ADJUSTMENT) so IP's destination TERRAIN does
        // not z-fight with the portal quad. The slot-1 sub-level cut sits exactly ON the plane
        // and keeps the complementary half, so inside a portal pass the two slots together
        // would leave a 0.01-block slab of the ship just past the plane: a thin rim for the
        // whole crossing. Undoing the adjustment for THIS scoped sub-level draw puts both cuts
        // on the same plane; restorePortalClip() restores slot 0 as soon as the bracket exits,
        // so the parent world's terrain keeps IP's back-shift.
        double exactW = worldEquation[3] - FrontClipping.ADJUSTMENT;
        portalInputProgram = shader.getId();
        portalInputEquation = new double[]{localNormal.x, localNormal.y, localNormal.z, exactW};
        uniform.set(
            (float) localNormal.x, (float) localNormal.y, (float) localNormal.z,
            (float) exactW
        );
        uniform.upload();
        return true;
    }

    /** Restore IP's world-space slot-0 equation after the scoped Sable draw. */
    public static void restorePortalClip(ShaderInstance shader, double[] worldEquation) {
        portalInputProgram = -1;
        portalInputEquation = null;
        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) return;
        uniform.set(
            (float) worldEquation[0], (float) worldEquation[1],
            (float) worldEquation[2], (float) worldEquation[3]
        );
        uniform.upload();
    }

    /**
     * Restore the per-sub-level clip uniform to "no clipping" {@code (0,0,0,1)} and
     * push to the GPU. Used by mixin RETURN paths so subsequent draws (vanilla
     * terrain after our sub-level render) don't inherit a stale equation.
     */
    public static void clearAndUpload() {
        // Clear the published equations first so subsequent program binds
        // outside the sub-level bracket fall back to portal-mirror or
        // no-clip writes in GlStateManagerMixin.
        currentSubLevelEqWorld = NONE;
        currentSubLevelEqEye = NONE;
        currentSubLevelEqVanillaInput = NONE;
        subLevelClip = false;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) IplProgramBindHook.prepareShader(shader);
        IplProgramBindHook.uploadSubLevel(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }
}
