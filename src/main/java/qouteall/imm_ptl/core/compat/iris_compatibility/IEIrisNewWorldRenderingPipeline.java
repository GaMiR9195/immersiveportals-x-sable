package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.targets.RenderTargets;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface IEIrisNewWorldRenderingPipeline {
    void ip_setIsRenderingWorld(boolean cond);

    RenderTargets ip_getRenderTargets();

    CenterDepthSampler ip_getCenterDepthSampler();

    @Nullable ShaderStorageBufferHolder ip_getStorageBuffers();

    Set<GlImage> ip_getCustomImages();
}
