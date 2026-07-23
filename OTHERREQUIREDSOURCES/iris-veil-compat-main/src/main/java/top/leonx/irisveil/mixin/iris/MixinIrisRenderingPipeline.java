package top.leonx.irisveil.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.compat.veil.IrisVeilShaderCache;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Mixin(IrisRenderingPipeline.class)
public abstract class MixinIrisRenderingPipeline implements IrisRenderingPipelineAccessor {
    @Shadow(remap = false)
    @Final
    private RenderTargets renderTargets;

    @Shadow(remap = false)
    @Final
    private ImmutableSet<Integer> flippedAfterTranslucent;

    @Unique
    private ProgramSet programSet;

    @Unique
    private Map<String, GlFramebuffer> irisveil$compatGbufferTargets;

    @Override
    public ProgramSet getProgramSet(){
        return programSet;
    }

    @Inject(method = "<init>",at = @At("TAIL"),remap = false)
    public void initSet(ProgramSet set, CallbackInfo callbackInfo){
        programSet = set;
        // Iris pipeline (re)created → shaderpack changed → invalidate Veil shader cache
        // Catch Throwable to handle NoClassDefFoundError when Veil is not loaded
        try {
            IrisVeilShaderCache.onShaderPackReload();
        } catch (Throwable e) {
            // Veil not present, silently ignore
        }
    }

    @Override
    public void irisveil$bindCompatGbufferFramebuffer(int[] drawBuffers) {
        if (irisveil$compatGbufferTargets == null) {
            irisveil$compatGbufferTargets = new HashMap<>();
        }

        int[] drawBuffersCopy = drawBuffers.clone();
        String key = Arrays.toString(drawBuffersCopy);
        GlFramebuffer target = irisveil$compatGbufferTargets.computeIfAbsent(
            key,
            ignored -> renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, drawBuffersCopy));
        target.bind();
    }

    @Invoker(remap = false)
    @Override
    public abstract ShaderInstance invokeCreateShader(String name, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
                                                    VertexFormat vertexFormat, FogMode fogMode,
                                                    boolean isIntensity, boolean isFullbright, boolean isGlint,
                                                    boolean isText, boolean isIE) throws IOException;

    @Invoker(remap = false)
    @Override
    public abstract ShaderInstance invokeCreateShadowShader(String name, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
                                                            VertexFormat vertexFormat, boolean isIntensity, boolean isFullbright,
                                                            boolean isText, boolean isIE) throws IOException;

}
