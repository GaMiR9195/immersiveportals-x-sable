package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.ProgramManager;
import ipl.sable.render.IplProgramBindHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ProgramManager/Iris bind path; delegates to the same two-plane uploader as vanilla. */
@Mixin(value = ProgramManager.class, remap = false)
public class IplProgramManagerProbeMixin {

    @Inject(
        method = "glUseProgram(I)V",
        at = @At("HEAD")
    )
    private static void ipl$onProgramManagerUseProgram(int program, CallbackInfo ci) {
        IplProgramBindHook.onBind(program);
    }
}
