package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.function.Supplier;

@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem_Clipping {
    @Inject(
        method = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V",
        at = @At("RETURN")
    )
    private static void onSetShader(Supplier<ShaderInstance> supplier, CallbackInfo ci) {
        if (IPGlobal.enableClippingMechanism) {
            if (!IrisInterface.invoker.isIrisPresent()) {
                if (CrossPortalEntityRenderer.isRenderingEntityNormally ||
                    CrossPortalEntityRenderer.isRenderingEntityProjection
                ) {
                    FrontClipping.updateClippingEquationUniformForCurrentShader(true);
                }
                else if (RenderStates.isRenderingPortalWeather) {
                    FrontClipping.updateClippingEquationUniformForCurrentShader(false);
                }
                else if (FrontClipping.isClippingEnabled) {
                    // BUG FIX (sable fork): during a normal portal-through render,
                    // the original code hit the `else` below and called
                    // unsetClippingUniform() on every shader bind. That zeroes
                    // iportal_ClippingEquation on the just-bound shader. Terrain
                    // works because MixinLevelRenderer_Optional re-sets the uniform
                    // before each renderSectionLayer apply(), but block-entity and
                    // entity passes bind their shaders via this setShader hook and
                    // then draw without a full apply in between -- so the uniform
                    // stays zeroed and BEs render unclipped through the portal.
                    //
                    // Fix: when FrontClipping is currently active (portal-through
                    // bracket is set up), propagate the equation to whichever
                    // shader was just bound. updateClippingEquationUniformForCurrentShader
                    // now also calls upload() so the GPU sees the value before
                    // the next draw, not just the CPU side.
                    FrontClipping.updateClippingEquationUniformForCurrentShader(false);
                }
                else {
                    FrontClipping.unsetClippingUniform();
                }
            }
            else {
                // Iris path: same fix. Under Iris IP previously unconditionally
                // unset; this assumed shaderpack-side handling would cover
                // everything but in practice it only covers terrain. For
                // entities / block entities under Iris we'd still want IP's
                // slot 0 active. The Iris-rewritten shader names (entities_*)
                // still need to be in IP's affectedShaders list to declare the
                // uniform in their GLSL, but that's already handled via our
                // YAML extension.
                if (FrontClipping.isClippingEnabled) {
                    FrontClipping.updateClippingEquationUniformForCurrentShader(false);
                } else {
                    FrontClipping.unsetClippingUniform();
                }
            }
        }

    }
}
