package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pathways.CenterDepthSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CenterDepthSampler.class, remap = false)
public interface AccessorIrisCenterDepthSampler {
    /** The smoothed center depth carried to the next frame (1x1, R32F). */
    @Accessor("altTexture")
    int ip_getSmoothedDepthTexture();
}
