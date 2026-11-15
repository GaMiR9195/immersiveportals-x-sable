package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.render.IplProgramBindHook;
import ipl.sable.render.clip.IplClipPlanes;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * The clip uniform belongs to {@code IplProgramBindHook}, nobody else may write it.
 *
 * <p>Veil discovers every active uniform of a vanilla program ({@code updateLocations} TAIL) and
 * gives the ones it does not know a vanilla {@link Uniform} filled with ZEROS, which it uploads at
 * the end of every {@code apply()} — after the program was bound and the bind hook had uploaded
 * the planes. The clip uniform of every vanilla shader (sub-levels, entities, block entities,
 * particles, everything drawn through {@code ShaderInstance}) was overwritten with zero planes:
 * {@code dot(gl_Position, 0) = 0} keeps every vertex, so nothing drawn with them was ever cut in a
 * portal pass. Measured: the uniform read back from the GPU was all zeros while the planes were
 * right.
 *
 * <ul>
 *   <li>Veil's uniforms for the clip planes are disarmed (location -1: their uploads are no-ops).</li>
 *   <li>After every {@code apply()} (after Veil's and vanilla's uniform uploads) the planes are
 *       uploaded again, so no other uniform writer can win.</li>
 * </ul>
 * Priority above Veil's mixin (800): both TAIL handlers run after Veil's.
 */
@Mixin(value = ShaderInstance.class, priority = 2000)
public abstract class ShaderInstanceClipMixin {

    @Shadow
    @Final
    public Map<String, Uniform> uniformMap;

    @Inject(method = "updateLocations", at = @At("TAIL"), require = 1)
    private void ipl$disarmForeignClipUniforms(CallbackInfo ci) {
        for (Map.Entry<String, Uniform> entry : this.uniformMap.entrySet()) {
            if (entry.getKey().startsWith(IplClipPlanes.UNIFORM)) {
                entry.getValue().setLocation(-1);
            }
        }
    }

    @Inject(method = "apply", at = @At("TAIL"), require = 1)
    private void ipl$reapplyClipPlanes(CallbackInfo ci) {
        IplProgramBindHook.afterShaderApply();
    }
}
