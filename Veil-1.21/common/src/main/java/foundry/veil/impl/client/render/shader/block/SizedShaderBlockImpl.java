package foundry.veil.impl.client.render.shader.block;

import foundry.veil.api.client.render.VeilRenderSystem;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;

@ApiStatus.Internal
public class SizedShaderBlockImpl<T> extends ShaderBlockImpl<T> {

    private final int size;
    private final ByteBuffer upload;
    private final BiConsumer<T, ByteBuffer> serializer;

    public SizedShaderBlockImpl(BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        super(binding);
        this.size = size;
        this.upload = MemoryUtil.memAlloc(this.size);
        this.serializer = serializer;
    }

    @Override
    public void bind(int index) {
        int binding = this.binding.getGlType();
        Validate.inclusiveBetween(0, VeilRenderSystem.maxTargetBindings(binding), index);

        if (this.buffer == 0) {
            this.dirty = true;

            StorageType storageType = getStorageType();
            this.buffer = storageType.createBuffer(binding);
            storageType.resize(binding, this.buffer, this.size);
        }

        if (this.dirty) {
            this.dirty = false;

            if (this.value != null) {
                this.serializer.accept(this.value, this.upload);
                this.upload.rewind();
            } else {
                MemoryUtil.memSet(this.upload, 0);
            }

            getStorageType().write(binding, this.buffer, this.upload);
        }

        glBindBufferBase(binding, index, this.buffer);
    }

    @Override
    public void unbind(int index) {
        int binding = this.binding.getGlType();
        Validate.inclusiveBetween(0, VeilRenderSystem.maxTargetBindings(binding), index);
        glBindBufferBase(binding, index, 0);
    }

    @Override
    public void free() {
        super.free();
        MemoryUtil.memFree(this.upload);
    }
}
