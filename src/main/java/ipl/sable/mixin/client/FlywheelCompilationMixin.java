package ipl.sable.mixin.client;

import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import ipl.sable.render.clip.IplClipShaderInjector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * Flywheel compiles its programs itself, past Mojang's {@code Program}, Veil's preprocessor and
 * Iris's transformer: its vertex shaders (instancing and indirect backends alike) get the clip
 * writes of {@link IplClipShaderInjector} right before compilation.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation", remap = false)
public abstract class FlywheelCompilationMixin {

	@Shadow
	@Final
	private StringBuilder fullSource;

	@Inject(method = "compile", at = @At("HEAD"))
	private void ipl$injectClipPlanes(ShaderType shaderType, String name, CallbackInfoReturnable<?> cir) {
		if (!IPGlobal.enableClippingMechanism || shaderType != ShaderType.VERTEX) return;
		String injected = IplClipShaderInjector.inject(IplClipShaderInjector.Stage.VERTEX, fullSource.toString());
		fullSource.setLength(0);
		fullSource.append(injected);
	}
}
