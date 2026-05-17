package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

/**
 * Registers our own per-sub-level clip uniform on each {@link ShaderInstance}.
 *
 * <p>Mirrors IP's approach in {@code qouteall.imm_ptl.core.mixin.client.render.shader.MixinShaderInstance}
 * which adds the {@code iportal_ClippingEquation} uniform. We add a parallel
 * uniform {@code ipl_subLevelClipEquation} on the same shaders so the GLSL the
 * shader-source transformation in {@code shader_transformation.yaml} injects
 * has a backing {@link Uniform} object for our render code to {@code set()} /
 * {@code upload()}.
 *
 * <p><b>Scope:</b> we only add the uniform when
 * {@link ShaderCodeTransformation#shouldAddUniform} returns true, so we don't
 * pay the cost on shaders that never see our discard injection. (And the shader
 * compile would fail if the uniform Java object referenced a name not in the
 * GLSL source.)
 *
 * <p>Note this mixin doesn't conflict with IP's mixin on the same method --
 * mixin multi-injects are fine as long as the injected method bodies don't
 * collide on shared state, and they don't here (each adds a distinct uniform
 * to {@code uniforms}).
 */
@Mixin(ShaderInstance.class)
public abstract class IplShaderInstanceClipMixin implements IplSubLevelClipShader {

    @Shadow
    @Final
    private List<Uniform> uniforms;

    @Shadow
    @Final
    private String name;

    @Unique
    @Nullable
    private Uniform ipl$subLevelClipEquation;

    /**
     * Set of shader names whose GLSL source actually gets {@code ipl_subLevelClipEquation}
     * declared by our transformation entry. Must stay in sync with the affectedShaders
     * list in our {@code shader_transformation.yaml} entry that injects our slot-1
     * uniform + write.
     *
     * <p>We can't reuse IP's {@code ShaderCodeTransformation.shouldAddUniform(name)} as
     * a filter here -- that returns true for any shader in any of IP's entries, including
     * IP-only entries whose GLSL doesn't declare our uniform. Registering a Java-side
     * {@code Uniform} on those shaders would log "Shader X could not find uniform
     * named ipl_subLevelClipEquation" warnings on every compile.
     */
    @Unique
    private static final Set<String> IPL$AFFECTED_SHADERS = Set.of(
        "terrain_solid",
        "terrain_cutout",
        "terrain_translucent",
        "entities_solid",
        "entities_cutout",
        "entities_translucent"
    );

    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("HEAD")
    )
    private void ipl$onLoadReferences(CallbackInfo ci) {
        Shader self = (Shader) (Object) this;

        if (IPL$AFFECTED_SHADERS.contains(name)) {
            // type=7 (UT_FLOAT4), count=4 -- matches IP's Uniform configuration for
            // its own clipping equation uniform, which has the same vec4 layout.
            ipl$subLevelClipEquation = new Uniform(
                "ipl_subLevelClipEquation",
                7, 4, self
            );
            uniforms.add(ipl$subLevelClipEquation);
        }
    }

    @Nullable
    @Override
    public Uniform ipl$getSubLevelClipUniform() {
        return ipl$subLevelClipEquation;
    }
}
