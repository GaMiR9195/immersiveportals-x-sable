package foundry.veil.impl.client.render.shader.block;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL15C.*;

/**
 * Abstract implementation of {@link ShaderBlock}.
 *
 * @param <T> The type of object to serialize
 * @author Ocelot
 */
@ApiStatus.Internal
public abstract class ShaderBlockImpl<T> implements ShaderBlock<T> {

    private static StorageType storageType;

    protected final BufferBinding binding;
    protected int buffer;
    protected T value;
    protected boolean dirty;

    private int index;

    protected ShaderBlockImpl(BufferBinding binding) {
        this.binding = binding;
        this.buffer = 0;
        this.value = null;
        this.dirty = false;
        this.index = -1;
    }

    protected static StorageType getStorageType() {
        if (storageType == null) {
            storageType = VeilRenderSystem.directStateAccessSupported() ? StorageType.DSA : StorageType.LEGACY;
        }
        return storageType;
    }

    @Override
    public void set(@Nullable T value) {
        this.value = value;
        this.dirty = true;
    }

    /**
     * Binds this block to the specified index.
     *
     * @param index The index to bind this block to
     */
    public abstract void bind(int index);

    /**
     * Unbinds this block from the specified index.
     *
     * @param index The index to unbind this block from
     */
    public abstract void unbind(int index);

    public BufferBinding getBinding() {
        return this.binding;
    }

    public int getIndex() {
        return this.index;
    }

    @Override
    public @Nullable T getValue() {
        return this.value;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    @Override
    public void free() {
        VeilRenderSystem.unbind(this);
        if (this.buffer != 0) {
            GlStateManager._glDeleteBuffers(this.buffer);
            this.buffer = 0;
        }
    }

    public enum StorageType {
        LEGACY {
            @Override
            public int createBuffer(int binding) {
                return glGenBuffers();
            }

            @Override
            public void resize(int binding, int buffer, long size) {
                RenderSystem.glBindBuffer(binding, buffer);
                glBufferData(binding, size, GL_DYNAMIC_DRAW);
            }

            @Override
            public void write(int binding, int buffer, ByteBuffer upload) {
                RenderSystem.glBindBuffer(binding, buffer);
                glBufferSubData(binding, 0L, upload);
            }
        },
        DSA {
            @Override
            public int createBuffer(int binding) {
                return glCreateBuffers();
            }

            @Override
            public void resize(int binding, int buffer, long size) {
                glNamedBufferData(buffer, size, GL_DYNAMIC_DRAW);
            }

            @Override
            public void write(int binding, int buffer, ByteBuffer upload) {
                glNamedBufferSubData(buffer, 0L, upload);
            }
        };

        public abstract int createBuffer(int binding);

        public abstract void resize(int binding, int buffer, long size);

        public abstract void write(int binding, int buffer, ByteBuffer upload);
    }
}
