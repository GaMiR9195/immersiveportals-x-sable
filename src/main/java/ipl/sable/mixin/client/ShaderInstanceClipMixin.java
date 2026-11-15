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
 * The clip uniform belongs to {@code IplProgramBindHook}; nobody else may write it.
 *
 * <p>Veil discovers every active uniform of a vanilla program ({@code updateLocations} TAIL) and
 * gives the ones it does not know a vanilla {@link Uniform} filled with zeros, uploaded at the end
 * of every {@code apply()}, after the bind hook has uploaded the planes. Zero planes keep every
 * vertex ({@code dot(gl_Position, 0) = 0}), so everything drawn through {@code ShaderInstance}
 * (sub-levels, entities, block entities, particles) would escape the portal cut.
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

    @Shadow
    public abstract int getId();

    @Inject(method = "updateLocations", at = @At("TAIL"), require = 1)
    private void ipl$disarmForeignClipUniforms(CallbackInfo ci) {
        IplProgramBindHook.forgetProgram(getId());
        for (Map.Entry<String, Uniform> entry : this.uniformMap.entrySet()) {
            if (entry.getKey().startsWith(IplClipPlanes.UNIFORM)) {
                entry.getValue().setLocation(-1);
            }
        }
    }

    @Inject(method = "apply", at = @At("TAIL"), require = 1)
    private void ipl$reapplyClipPlanes(CallbackInfo ci) {
        IplProgramBindHook.afterShaderApply(getId());
    }
}
