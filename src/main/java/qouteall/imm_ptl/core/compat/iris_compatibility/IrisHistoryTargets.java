package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.platform.GlStateManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.mixin.iris.AccessorIrisCenterDepthSampler;
import qouteall.imm_ptl.core.compat.mixin.iris.AccessorIrisRenderTargets;
import qouteall.imm_ptl.core.compat.mixin.iris.AccessorIrisShaderStorageBufferHolder;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps the persistent GPU state of each view apart.
 *
 * <p>Packs carry data into the next frame in render targets they never clear
 * ({@code colortexNClear = false}: TAA history, exposure, reprojection), in custom images that are
 * not cleared, in shader storage buffers, and in the smoothed center depth (depth of field). Iris
 * holds one copy of each per pipeline. When a pipeline starts a view other than the one its
 * resources hold, the holder's state is saved into its {@link IrisViewContexts.Context} and the
 * starting view's state is put back.
 *
 * <p>A pipeline that renders a single view per frame (the player's view, or a portal into
 * another dimension) copies nothing; only views sharing a pipeline pay two copies per resource.
 */
public final class IrisHistoryTargets {
    private static final IdentityHashMap<IrisRenderingPipeline, IrisViewContexts.Context> HOLDERS =
        new IdentityHashMap<>();
    private static @Nullable Boolean supported;

    private IrisHistoryTargets() {}

    /** Called when the pipeline starts a view, before any pass reads its resources. */
    public static void onBeginLevelRendering(IrisRenderingPipeline pipeline) {
        // The experimental renderer draws portals into the main view's own frame.
        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer || !isSupported()) return;

        IrisViewContexts.Context active = IrisViewContexts.active();
        IrisViewContexts.Context holder = HOLDERS.put(pipeline, active);
        if (holder == null || holder == active) return;

        // Shader writes to buffers and images must land before they are copied.
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
        if (!holder.isDisposed()) {
            holder.attachment(pipeline, History::new).saveFrom(pipeline);
        }
        History history = active.peekAttachment(pipeline);
        if (history != null) {
            history.loadInto(pipeline);
        }
    }

    public static void reset() {
        HOLDERS.clear();
    }

    private static boolean isSupported() {
        if (supported == null) {
            supported = GL.getCapabilities().glCopyImageSubData != 0;
        }
        return supported;
    }

    private static IEIrisNewWorldRenderingPipeline access(IrisRenderingPipeline pipeline) {
        return (IEIrisNewWorldRenderingPipeline) (Object) pipeline;
    }

    private static int smoothedDepthOf(IrisRenderingPipeline pipeline) {
        CenterDepthSampler sampler = access(pipeline).ip_getCenterDepthSampler();
        return sampler == null ? 0 : ((AccessorIrisCenterDepthSampler) sampler).ip_getSmoothedDepthTexture();
    }

    private static ShaderStorageBuffer[] storageBuffersOf(IrisRenderingPipeline pipeline) {
        ShaderStorageBufferHolder holder = access(pipeline).ip_getStorageBuffers();
        if (holder == null) return new ShaderStorageBuffer[0];
        ShaderStorageBuffer[] buffers = ((AccessorIrisShaderStorageBufferHolder) holder).ip_getBuffers();
        return buffers == null ? new ShaderStorageBuffer[0] : buffers;
    }

    private static void copyTexture(int target, int from, int to, int width, int height, int depth) {
        GL43C.glCopyImageSubData(
            from, target, 0, 0, 0, 0,
            to, target, 0, 0, 0, 0,
            width, height, depth
        );
    }

    private static final class History implements AutoCloseable {
        private final Int2ObjectOpenHashMap<Texture> colors = new Int2ObjectOpenHashMap<>();
        private final IdentityHashMap<GlImage, Texture> images = new IdentityHashMap<>();
        private final Int2ObjectOpenHashMap<Buffer> buffers = new Int2ObjectOpenHashMap<>();
        private @Nullable Texture smoothedDepth;

        void saveFrom(IrisRenderingPipeline pipeline) {
            RenderTargets targets = access(pipeline).ip_getRenderTargets();
            for (Map.Entry<Integer, PackRenderTargetDirectives.RenderTargetSettings> entry :
                ((AccessorIrisRenderTargets) targets).ip_getTargetSettings().entrySet()) {
                if (entry.getValue().shouldClear()) continue;
                RenderTarget target = targets.get(entry.getKey());
                if (target == null) continue;
                Texture copy = Texture.reuse(
                    colors.get(entry.getKey().intValue()), GL11.GL_TEXTURE_2D,
                    target.getInternalFormat().getGlFormat(), target.getWidth(), target.getHeight(), 1
                );
                colors.put(entry.getKey().intValue(), copy);
                copy.copyFrom(target.getMainTexture());
            }

            for (GlImage image : access(pipeline).ip_getCustomImages()) {
                if (image.shouldClear()) continue;
                Texture copy = Texture.reuseFor(images.get(image), image);
                images.put(image, copy);
                copy.copyFrom(image.getId());
            }

            for (ShaderStorageBuffer source : storageBuffersOf(pipeline)) {
                if (source == null) continue;
                long size = Buffer.sizeOf(source.getId());
                Buffer copy = buffers.get(source.getIndex());
                if (copy == null || copy.size != size) {
                    if (copy != null) copy.close();
                    copy = new Buffer(size);
                    buffers.put(source.getIndex(), copy);
                }
                Buffer.copy(source.getId(), copy.id, size);
            }

            int depth = smoothedDepthOf(pipeline);
            if (depth != 0) {
                smoothedDepth = Texture.reuse(smoothedDepth, GL11.GL_TEXTURE_2D, GL30.GL_R32F, 1, 1, 1);
                smoothedDepth.copyFrom(depth);
            }
        }

        void loadInto(IrisRenderingPipeline pipeline) {
            RenderTargets targets = access(pipeline).ip_getRenderTargets();
            for (Int2ObjectOpenHashMap.Entry<Texture> entry : colors.int2ObjectEntrySet()) {
                RenderTarget target = targets.get(entry.getIntKey());
                Texture copy = entry.getValue();
                if (target != null && copy.fits(GL11.GL_TEXTURE_2D,
                    target.getInternalFormat().getGlFormat(), target.getWidth(), target.getHeight(), 1)) {
                    copy.copyTo(target.getMainTexture());
                }
            }

            for (GlImage image : access(pipeline).ip_getCustomImages()) {
                Texture copy = images.get(image);
                if (copy != null && copy.fits(image)) {
                    copy.copyTo(image.getId());
                }
            }

            for (ShaderStorageBuffer target : storageBuffersOf(pipeline)) {
                if (target == null) continue;
                Buffer copy = buffers.get(target.getIndex());
                if (copy != null && copy.size == Buffer.sizeOf(target.getId())) {
                    Buffer.copy(copy.id, target.getId(), copy.size);
                }
            }

            int depth = smoothedDepthOf(pipeline);
            if (depth != 0 && smoothedDepth != null) {
                smoothedDepth.copyTo(depth);
            }
        }

        @Override
        public void close() {
            colors.values().forEach(Texture::close);
            colors.clear();
            images.values().forEach(Texture::close);
            images.clear();
            buffers.values().forEach(Buffer::close);
            buffers.clear();
            if (smoothedDepth != null) {
                smoothedDepth.close();
                smoothedDepth = null;
            }
        }
    }

    private static final class Texture implements AutoCloseable {
        final int id;
        final int target;
        final int internalFormat;
        final int width;
        final int height;
        final int depth;

        private Texture(int target, int internalFormat, int width, int height, int depth) {
            this.target = target;
            this.internalFormat = internalFormat;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.id = GlStateManager._genTexture();
            int storage = sized(internalFormat);
            bind(target, id);
            switch (target) {
                case GL11.GL_TEXTURE_1D -> GL42C.glTexStorage1D(target, 1, storage, width);
                case GL12.GL_TEXTURE_3D -> GL42C.glTexStorage3D(target, 1, storage, width, height, depth);
                default -> GL42C.glTexStorage2D(target, 1, storage, width, height);
            }
            bind(target, 0);
        }

        /** Immutable storage needs a sized format; Iris may use unsized ones, stored as 8 bits. */
        private static int sized(int format) {
            return switch (format) {
                case GL11.GL_RGBA -> GL11.GL_RGBA8;
                case GL11.GL_RGB -> GL11.GL_RGB8;
                case GL30.GL_RG -> GL30.GL_RG8;
                case GL11.GL_RED -> GL30.GL_R8;
                default -> format;
            };
        }

        static Texture reuse(@Nullable Texture old, int target, int internalFormat, int width, int height, int depth) {
            if (old != null && old.fits(target, internalFormat, width, height, depth)) return old;
            if (old != null) old.close();
            return new Texture(target, internalFormat, width, height, depth);
        }

        static Texture reuseFor(@Nullable Texture old, GlImage image) {
            int target = image.getTarget().getGlType();
            int[] size = sizeOf(target, image.getId());
            return reuse(old, target, image.getInternalFormat().getGlFormat(), size[0], size[1], size[2]);
        }

        boolean fits(int target, int internalFormat, int width, int height, int depth) {
            return this.target == target && this.internalFormat == internalFormat
                && this.width == width && this.height == height && this.depth == depth;
        }

        boolean fits(GlImage image) {
            int target = image.getTarget().getGlType();
            int[] size = sizeOf(target, image.getId());
            return fits(target, image.getInternalFormat().getGlFormat(), size[0], size[1], size[2]);
        }

        void copyFrom(int source) {
            copyTexture(target, source, id, width, height, depth);
        }

        void copyTo(int destination) {
            copyTexture(target, id, destination, width, height, depth);
        }

        private static int[] sizeOf(int target, int texture) {
            bind(target, texture);
            int[] size = {
                Math.max(1, GL11.glGetTexLevelParameteri(target, 0, GL11.GL_TEXTURE_WIDTH)),
                Math.max(1, GL11.glGetTexLevelParameteri(target, 0, GL11.GL_TEXTURE_HEIGHT)),
                Math.max(1, GL11.glGetTexLevelParameteri(target, 0, GL12.GL_TEXTURE_DEPTH))
            };
            bind(target, 0);
            return size;
        }

        /** 2D bindings go through the state cache; other targets are not tracked by it. */
        private static void bind(int target, int texture) {
            if (target == GL11.GL_TEXTURE_2D) {
                GlStateManager._bindTexture(texture);
            }
            else {
                GL11.glBindTexture(target, texture);
            }
        }

        @Override
        public void close() {
            GlStateManager._deleteTexture(id);
        }
    }

    private static final class Buffer implements AutoCloseable {
        final int id;
        final long size;

        Buffer(long size) {
            this.size = size;
            this.id = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, id);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, size, GL15C.GL_STATIC_COPY);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        }

        static long sizeOf(int buffer) {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
            long size = GL15C.glGetBufferParameteri(GL31C.GL_COPY_READ_BUFFER, GL15C.GL_BUFFER_SIZE);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            return size;
        }

        static void copy(int from, int to, long size) {
            if (size <= 0) return;
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, from);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, to);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, size);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        }

        @Override
        public void close() {
            GL15C.glDeleteBuffers(id);
        }
    }
}
