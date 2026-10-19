package ipl.sable.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import ipl.sable.render.IplProgramBindHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same implementation as ProgramManager's hook; includes BOTH sub-level clip planes. */
@Mixin(value = GlStateManager.class, remap = false)
public class IplGlUseProgramProbeMixin {
    @Inject(method = "_glUseProgram(I)V", at = @At("HEAD"))
    private static void ipl$writeClipEquationToProgram(int program, CallbackInfo ci) {
        IplProgramBindHook.onBind(program);
    }

    @Inject(method = "glDeleteProgram(I)V", at = @At("HEAD"), require = 0)
    private static void ipl$forgetDeletedProgram(int program, CallbackInfo ci) {
        IplProgramBindHook.forgetProgram(program);
        ipl.sable.render.IplProgramRegistry.unregister(program);
    }
}
