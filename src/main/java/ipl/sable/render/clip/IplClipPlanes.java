package ipl.sable.render.clip;

import ipl.sable.render.IplProgramBindHook;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector4d;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

import java.util.ArrayDeque;

/**
 * The single source of truth for every geometric cut of the frame:
 * <ul>
 *   <li>{@link #PORTAL}: the front clip of the portal pass being drawn. It belongs to the WHOLE
 *       pass and is never replaced by a draw's own cut, only nudged by the terrain z-fight
 *       adjustment; outside portal passes it is empty.</li>
 *   <li>{@link #OBJECT}: the cut of one object being drawn (an entity crossing a portal, the
 *       outer clip of a crossing entity, the far-side image of an entity seen through another
 *       portal). It is ANDed with the pass clip, so nothing an object does can make the pass
 *       leak geometry from behind the destination plane.</li>
 *   <li>the remaining slots: the aperture cuts of the Sable piece being drawn.</li>
 * </ul>
 *
 * <p>Planes are given as camera-relative world equations {@code (n, d)}, kept where
 * {@code n·p + d >= 0}, and belong to the level pass they were set in: every
 * {@code LevelRenderer.renderLevel} opens a {@link #beginPass pass} with its own projection and
 * view, nested portal passes stack, and closing a pass restores the planes of the enclosing one.
 * Shaders receive them in clip space ({@link IplClipShaderInjector}), so the cut is applied at
 * draw time to whatever reaches the rasterizer: it does not depend on when a renderer flushes
 * its buffers or which shader it uses.
 *
 * <p>Renderers that project with their own matrices (LOD renderers such as Distance Horizons or
 * Voxy use their own near/far planes) inject the same GLSL and upload
 * {@link #clipSpaceEquations(Matrix4fc, float[])} for their view-projection after binding.
 *
 * <p>Render thread only.
 */
public final class IplClipPlanes {

	public static final String UNIFORM = "ipl_ClipPlanes";
	public static final int SLOTS = 4;
	public static final int PORTAL = 0;
	public static final int OBJECT = 1;
	public static final int FIRST_PIECE_CUT = 2;
	public static final int PIECE_CUTS = SLOTS - FIRST_PIECE_CUT;

	private static final double[] NEUTRAL = {0.0, 0.0, 0.0, 1.0};

	/** One level render: camera-relative world → clip space, and the planes set inside it. */
	private static final class Pass {
		final Matrix4d viewProjection;
		final double[][] planes = new double[SLOTS][];

		Pass(Matrix4d viewProjection) {
			this.viewProjection = viewProjection;
		}
	}

	private static final ArrayDeque<Pass> PASSES = new ArrayDeque<>();
	private static int suppression;
	private static final float[] CLIP_SPACE = new float[SLOTS * 4];
	private static long revision;
	private static long clipSpaceRevision = -1;

	private IplClipPlanes() {}

	/** Opens the pass of a level render; {@code view} is the camera rotation it draws with. */
	public static void beginPass(Matrix4fc projection, Matrix4fc view) {
		boolean wasActive = anyActive();
		PASSES.push(new Pass(new Matrix4d(projection).mul(new Matrix4d(view))));
		changed(wasActive);
	}

	/** Closes the innermost pass; the enclosing pass's planes apply again. */
	public static void endPass() {
		if (PASSES.isEmpty()) return;
		boolean wasActive = anyActive();
		PASSES.pop();
		changed(wasActive);
	}

	/** Sets (or clears with null) one slot of the current pass. Ignored outside a level render. */
	public static void setPlane(int slot, double @Nullable [] cameraRelativeEquation) {
		Pass pass = PASSES.peek();
		if (pass == null) return;
		double[] old = pass.planes[slot];
		if (old == null && cameraRelativeEquation == null) return;
		if (old != null && cameraRelativeEquation != null && java.util.Arrays.equals(old, cameraRelativeEquation)) return;
		boolean wasActive = anyActive();
		pass.planes[slot] = cameraRelativeEquation == null ? null : cameraRelativeEquation.clone();
		changed(wasActive);
	}

	public static void clearPieceCuts() {
		for (int i = FIRST_PIECE_CUT; i < SLOTS; i++) setPlane(i, null);
	}

	/** Draws in screen space (sky, post effects, full-screen passes) ignore every cut. */
	public static void suppress() {
		boolean wasActive = anyActive();
		suppression++;
		changed(wasActive);
	}

	public static void unsuppress() {
		if (suppression == 0) return;
		boolean wasActive = anyActive();
		suppression--;
		changed(wasActive);
	}

	public static void suppressed(Runnable draw) {
		suppress();
		try {
			draw.run();
		} finally {
			unsuppress();
		}
	}

	/** Whether draws are cut by {@code slot} now (never inside a shadow-map pass). */
	public static boolean isActive(int slot) {
		return hasPlane(slot) && !IrisInterface.invoker.isRenderingShadowMap();
	}

	private static boolean hasPlane(int slot) {
		Pass pass = PASSES.peek();
		return pass != null && pass.planes[slot] != null && suppression == 0;
	}

	/** The camera-relative equation in {@code slot} of the current pass, or null (diagnostics). */
	public static double @Nullable [] plane(int slot) {
		Pass pass = PASSES.peek();
		return pass == null || pass.planes[slot] == null ? null : pass.planes[slot].clone();
	}

	public static boolean anyActive() {
		for (int i = 0; i < SLOTS; i++) if (isActive(i)) return true;
		return false;
	}

	/** Changes with every change of planes, passes or suppression. */
	public static long revision() {
		return revision;
	}

	/** Clip-space equations of the current pass (neutral for inactive slots), shared buffer. */
	public static float[] clipSpaceEquations() {
		if (clipSpaceRevision != revision) {
			Pass pass = PASSES.peek();
			fill(pass == null ? null : pass.viewProjection, CLIP_SPACE);
			clipSpaceRevision = revision;
		}
		return CLIP_SPACE;
	}

	/**
	 * The current planes for a renderer projecting camera-relative world positions with
	 * {@code viewProjection} instead of the level's matrices.
	 */
	public static void clipSpaceEquations(Matrix4fc viewProjection, float[] out) {
		fill(new Matrix4d(viewProjection), out);
	}

	private static void fill(@Nullable Matrix4d viewProjection, float[] out) {
		Pass pass = PASSES.peek();
		// c = M·p  =>  e·p = e·M⁻¹c = (M⁻ᵀe)·c
		Matrix4d toClip = viewProjection == null ? null : new Matrix4d(viewProjection).invert().transpose();
		Vector4d v = new Vector4d();
		for (int i = 0; i < SLOTS; i++) {
			double[] e = pass != null && toClip != null && hasPlane(i) ? pass.planes[i] : NEUTRAL;
			if (e != NEUTRAL) {
				toClip.transform(v.set(e[0], e[1], e[2], e[3]));
			} else {
				v.set(NEUTRAL[0], NEUTRAL[1], NEUTRAL[2], NEUTRAL[3]);
			}
			out[i * 4] = (float) v.x;
			out[i * 4 + 1] = (float) v.y;
			out[i * 4 + 2] = (float) v.z;
			out[i * 4 + 3] = (float) v.w;
		}
	}

	private static void changed(boolean wasActive) {
		revision++;
		if (wasActive || anyActive()) IplProgramBindHook.refreshCurrentProgram();
	}
}
