package ipl.sable.render;

import ipl.sable.render.clip.IplClipPlanes;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * Applies {@link IplClipPlanes} to the program that draws: on every program bind of every
 * pipeline (vanilla/Iris/Flywheel/Veil through {@code GlStateManager._glUseProgram}, Sodium
 * through its own {@code GlProgram.bind}), after every {@code ShaderInstance.apply()}, right
 * before draws, and to the bound program whenever the planes change.
 *
 * <p>A program is clip-capable when it carries the injected {@value IplClipPlanes#UNIFORM}
 * uniform. Only then are the hardware clip distances enabled for it; any other program (compute,
 * screen-space, foreign pipelines) draws with them disabled, so it never reads clip distances it
 * does not write.
 *
 * <p>Uniform locations are looked up on every application, never cached per program id: a
 * program can be re-linked under the same id in the middle of a frame (Veil recompiles programs
 * on demand), which resets its uniforms and may move them. Render thread only.
 */
public final class IplProgramBindHook {

	private static final float[] FOREIGN_EQUATIONS = new float[IplClipPlanes.SLOTS * 4];

	/** Program and plane revision the last application was made for (draw-time check). */
	private static int verifiedProgram = -1;
	private static long verifiedRevision = Long.MIN_VALUE;
	/** Clip distances written last; only an all-off state that is already off is skipped. */
	private static int enabledMask;

	private IplProgramBindHook() {}

	/** After {@code program} became the current program. */
	public static void onBind(int program) {
		if (!IPGlobal.enableClippingMechanism) return;
		apply(program, null);
	}

	/**
	 * For renderers that bind their programs with raw GL and project with their own matrices
	 * (LOD renderers): call right after binding {@code program}; {@code viewProjection} maps
	 * camera-relative world positions (relative to the level camera) to that program's clip space.
	 */
	public static void onBind(int program, Matrix4fc viewProjection) {
		if (!IPGlobal.enableClippingMechanism) return;
		IplClipPlanes.clipSpaceEquations(viewProjection, FOREIGN_EQUATIONS);
		apply(program, FOREIGN_EQUATIONS);
	}

	/**
	 * Right before a draw: the program that draws must hold the current planes.
	 *
	 * <p>Programs also become current behind {@code GlStateManager}'s back: Veil's non-DSA uniform
	 * upload binds a program with raw GL and restores {@code ShaderInstance.lastProgramId} with raw
	 * GL, after which vanilla's {@code apply()} sees "already bound" and never binds again. Costs
	 * one {@code glGetInteger} per draw while a cut is active and nothing otherwise.
	 */
	public static void beforeDraw() {
		if (!IPGlobal.enableClippingMechanism || !IplClipPlanes.anyActive()) return;
		int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		if (program == verifiedProgram && IplClipPlanes.revision() == verifiedRevision) return;
		apply(program, null);
	}

	/**
	 * End of {@code ShaderInstance.apply()}: the uniform uploads of the shader (and of mods hooked
	 * into it) are done, so the planes are written last. Unconditional while a cut is active.
	 */
	public static void afterShaderApply() {
		if (!IPGlobal.enableClippingMechanism || !IplClipPlanes.anyActive()) return;
		apply(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), null);
	}

	/** The planes changed while some program may be bound. */
	public static void refreshCurrentProgram() {
		if (!IPGlobal.enableClippingMechanism) return;
		apply(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), null);
	}

	private static void apply(int program, float @Nullable [] equations) {
		verifiedProgram = equations == null ? program : -1;
		verifiedRevision = IplClipPlanes.revision();
		if (program <= 0 || !IplClipPlanes.anyActive()) {
			setEnabled(0);
			return;
		}
		int location = GL20.glGetUniformLocation(program, IplClipPlanes.UNIFORM);
		if (location < 0) {
			setEnabled(0);
			return;
		}
		GL20.glUniform4fv(location, equations != null ? equations : IplClipPlanes.clipSpaceEquations());
		IplClipDiagnostics.afterUpload(program, location);
		int mask = 0;
		for (int slot = 0; slot < IplClipPlanes.SLOTS; slot++) {
			if (IplClipPlanes.isActive(slot)) mask |= 1 << slot;
		}
		setEnabled(mask);
	}

	private static void setEnabled(int mask) {
		if (mask == 0 && enabledMask == 0) return;
		enabledMask = mask;
		for (int slot = 0; slot < IplClipPlanes.SLOTS; slot++) {
			if ((mask & (1 << slot)) != 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + slot);
			else GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + slot);
		}
	}
}
