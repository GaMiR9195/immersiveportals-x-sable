package ipl.sable.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import ipl.sable.render.IplProgramBindHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Program binds of vanilla, IP, Iris ({@code ProgramManager}), Flywheel and Veil all end in
 * {@code GlStateManager._glUseProgram}; the clip planes are applied once the program is current.
 */
@Mixin(value = GlStateManager.class, remap = false)
public class GlStateManagerMixin {
	@Inject(method = "_glUseProgram(I)V", at = @At("RETURN"))
	private static void ipl$applyClipPlanes(int program, CallbackInfo ci) {
		IplProgramBindHook.onBind(program);
	}

	/** Every vanilla-path draw (vertex buffers, buffer uploads): the drawing program holds the planes. */
	@Inject(method = "_drawElements(IIIJ)V", at = @At("HEAD"))
	private static void ipl$checkClipPlanes(int mode, int count, int type, long indices, CallbackInfo ci) {
		IplProgramBindHook.beforeDraw();
	}
}
