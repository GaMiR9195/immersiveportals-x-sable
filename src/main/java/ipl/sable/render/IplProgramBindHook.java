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
 * through its own {@code GlProgram.bind}) and to the bound program whenever the planes change.
 *
 * <p>A program is clip-capable when it carries the injected {@value IplClipPlanes#UNIFORM}
 * uniform. Only then are the hardware clip distances enabled for it; any other program (compute,
 * screen-space, foreign pipelines) draws with them disabled, so it never reads clip distances it
 * does not write. Nothing is cached per program id: GL reuses ids of programs deleted behind our
 * back, and a location lookup per bind only happens while a cut is active.
 */
public final class IplProgramBindHook {

	private static final int ALL_SLOTS_MASK = (1 << IplClipPlanes.SLOTS) - 1;
	/** Clip distances we left enabled; we are the only writer of these GL capabilities. */
	private static int enabledMask;
	private static final float[] FOREIGN_EQUATIONS = new float[IplClipPlanes.SLOTS * 4];

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

	/** The planes changed while some program may be bound. */
	public static void refreshCurrentProgram() {
		if (!IPGlobal.enableClippingMechanism) return;
		apply(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), null);
	}

	private static void apply(int program, float @Nullable [] equations) {
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
		int mask = 0;
		for (int slot = 0; slot < IplClipPlanes.SLOTS; slot++) {
			if (IplClipPlanes.isActive(slot)) mask |= 1 << slot;
		}
		setEnabled(mask);
	}

	private static void setEnabled(int mask) {
		int changed = (enabledMask ^ mask) & ALL_SLOTS_MASK;
		for (int slot = 0; slot < IplClipPlanes.SLOTS; slot++) {
			int bit = 1 << slot;
			if ((changed & bit) == 0) continue;
			if ((mask & bit) != 0) GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + slot);
			else GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + slot);
		}
		enabledMask = mask;
	}
}
