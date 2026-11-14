package qouteall.imm_ptl.core.compat.mixin.iris;

import ipl.sable.render.clip.IplClipShaderInjector;
import ipl.sable.render.clip.IplClipShaderInjector.Stage;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;

import java.util.Map;

/**
 * Every world program Iris builds (vanilla, Sodium and Distance Horizons patches) gets the clip
 * writes in its last vertex-processing stage; composite passes are screen-space and stay as is.
 */
@Pseudo
@Mixin(value = TransformPatcher.class, remap = false)
public class MixinIrisTransformPatcher {

	@Inject(method = "transformInternal", at = @At("RETURN"))
	private static void onTransformInternal(
		String name, Map<PatchShaderType, String> inputs,
		Parameters parameters, CallbackInfoReturnable<Map<PatchShaderType, String>> cir
	) {
		if (!IPGlobal.enableClippingMechanism || parameters.patch == Patch.COMPOSITE) return;
		Map<PatchShaderType, String> map = cir.getReturnValue();
		if (map == null) return;
		inject(map, PatchShaderType.VERTEX, Stage.VERTEX);
		inject(map, PatchShaderType.TESS_EVAL, Stage.TESSELLATION_EVALUATION);
		inject(map, PatchShaderType.GEOMETRY, Stage.GEOMETRY);
	}

	private static void inject(Map<PatchShaderType, String> map, PatchShaderType type, Stage stage) {
		String code = map.get(type);
		if (code != null) map.put(type, IplClipShaderInjector.inject(stage, code));
	}
}
