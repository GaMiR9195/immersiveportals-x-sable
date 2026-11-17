package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = RenderTargets.class, remap = false)
public interface AccessorIrisRenderTargets {
    @Accessor("targetSettingsMap")
    Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> ip_getTargetSettings();
}
