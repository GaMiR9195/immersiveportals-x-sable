package qouteall.imm_ptl.core.compat.mixin.sodium;

import ipl.sable.render.IplProgramBindHook;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium (and Iris on Sodium) bind chunk programs with raw GL, past GlStateManager. */
@Mixin(value = GlProgram.class, remap = false)
public class MixinSodiumGlProgram {
	@Inject(method = "bind", at = @At("RETURN"))
	private void ip_applyClipPlanes(CallbackInfo ci) {
		IplProgramBindHook.onBind(((GlProgram<?>) (Object) this).handle());
	}
}
