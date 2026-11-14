package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.shaders.Program;
import ipl.sable.render.clip.IplClipShaderInjector;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * Entry point of the shader pipelines that load GLSL as text (vanilla {@code Program}, Sodium's
 * loader, Veil's preprocessor): every world vertex shader gets the clip-plane writes of
 * {@link IplClipShaderInjector}. There is no per-shader list; post-processing programs, which
 * draw in screen space by definition, are compiled inside {@link #compilingScreenSpacePrograms}.
 */
public final class ShaderCodeTransformation {

	private static final ThreadLocal<int[]> SCREEN_SPACE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private ShaderCodeTransformation() {}

	public static String transform(Program.Type type, String shaderId, String inputCode) {
		if (!IPGlobal.enableClippingMechanism || type != Program.Type.VERTEX) return inputCode;
		if (SCREEN_SPACE_DEPTH.get()[0] > 0) return inputCode;
		return IplClipShaderInjector.inject(IplClipShaderInjector.Stage.VERTEX, inputCode);
	}

	/** Programs compiled while {@code compile} runs are screen-space and stay unclipped. */
	public static <T> T compilingScreenSpacePrograms(java.util.function.Supplier<T> compile) {
		int[] depth = SCREEN_SPACE_DEPTH.get();
		depth[0]++;
		try {
			return compile.get();
		} finally {
			depth[0]--;
		}
	}
}
