package foundry.veil.impl.client.render.shader.block;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.block.DynamicShaderBlock;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;

@ApiStatus.Internal
public class DynamicShaderBlockImpl<T> extends ShaderBlockImpl<T> implements DynamicShaderBlock<T> {

    private final BiConsumer<T, ByteBuffer> serializer;

    private int size;
    private boolean resized;
    private ByteBuffer upload;

    public DynamicShaderBlockImpl(BufferBinding binding, int initialSize, BiConsumer<T, ByteBuffer> serializer) {
        super(binding);
        this.serializer = serializer;
        this.size = initialSize;
        this.resized = false;
        this.upload = MemoryUtil.memAlloc(initialSize);
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public void setSize(int size) {
        this.size = size;
        this.resized = true;
        this.upload = MemoryUtil.memRealloc(this.upload, size);
    }

    @Override
    public void bind(int index) {
        int binding = this.binding.getGlType();
        Validate.inclusiveBetween(0, VeilRenderSystem.maxTargetBindings(binding), index);

        if (this.buffer == 0) {
            this.resized = true;
            this.buffer = getStorageType().createBuffer(binding);
        }

        if (this.resized) {
            this.resized = false;
            this.dirty = true;
            getStorageType().resize(binding, this.buffer, this.size);
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
