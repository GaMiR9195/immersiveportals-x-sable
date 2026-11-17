package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ShaderStorageBufferHolder.class, remap = false)
public interface AccessorIrisShaderStorageBufferHolder {
    @Accessor("buffers")
    ShaderStorageBuffer[] ip_getBuffers();
}
