package ipl.sable.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import ipl.sable.mixin.client.BufferBuilderAccessor;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-block vertex ranges of sub-level section meshes (README, section 8).
 *
 * <p>While a section of a sub-level plot compiles ({@code SectionCompilerMixin}), the compiler
 * notes where each block's quads start and how many it emitted in every layer; the ranges
 * travel with the mesh to its {@code VertexBuffer} ({@code MeshDataMixin}, {@code VertexBufferMixin}).
 * A piece of a body can then be drawn from the same buffer with one multi-draw over the
 * quads of its own blocks, instead of drawing everything and clipping with a plane.
 */
public final class IplSectionRanges {

    /** Duck on {@code MeshData} and {@code VertexBuffer}. */
    public interface Holder {
        @Nullable Ranges ipl$ranges();

        void ipl$setRanges(@Nullable Ranges ranges);
    }

    /** Duck on {@code VertexBuffer}: what a range draw needs from it. */
    public interface BufferAccess {
        @Nullable RenderSystem.AutoStorageIndexBuffer ipl$sequential();

        int ipl$indexBufferId();

        int ipl$indexCount();

        @Nullable VertexFormat.Mode ipl$mode();

        @Nullable Object ipl$plan();

        /** Quads in the order the buffer's own (sorted) index buffer draws them; null = compile order. */
        int @Nullable [] ipl$quadOrder();

        void ipl$setPlan(@Nullable Object plan);
    }

    /** Ranges of one compiled layer: {@link #count} blocks in compile order. */
    public static final class Ranges {
        public final int count;
        /** Index of the block in its section ({@code x | y << 4 | z << 8}). */
        final short[] block;
        final int[] firstQuad;
        final int[] quads;
        /** Unique per compile: plan caches key on it. */
        public final long version;

        Ranges(int count, short[] block, int[] firstQuad, int[] quads, long version) {
            this.count = count;
            this.block = block;
            this.firstQuad = firstQuad;
            this.quads = quads;
            this.version = version;
        }

        public int block(int i) {
            return block[i] & 0xFFFF;
        }

        public int firstQuad(int i) {
            return firstQuad[i];
        }

        public int quads(int i) {
            return quads[i];
        }
    }

    private static final AtomicLong VERSIONS = new AtomicLong();
    private static final ThreadLocal<Recorder> ACTIVE = new ThreadLocal<>();

    /**
     * Quads in the order a QUADS index buffer draws them: each quad is six indices
     * {@code 4q, 4q+1, 4q+2, 4q+2, 4q+3, 4q}, so its first index names it.
     */
    public static int[] quadOrder(ByteBuffer indices, VertexFormat.IndexType type) {
        ByteBuffer view = indices.duplicate().order(ByteOrder.nativeOrder());
        int start = view.position();
        int quads = view.remaining() / (6 * type.bytes);
        int[] order = new int[quads];
        for (int k = 0; k < quads; k++) {
            int at = start + k * 6 * type.bytes;
            int first = type == VertexFormat.IndexType.SHORT ? Short.toUnsignedInt(view.getShort(at)) : view.getInt(at);
            order[k] = first >>> 2;
        }
        return order;
    }

    private IplSectionRanges() {}

    /** HEAD of a section compile (any compiler; a stale recorder never survives into another). */
    public static void begin(boolean recording) {
        if (recording) ACTIVE.set(new Recorder());
        else ACTIVE.remove();
    }

    public static void end() {
        ACTIVE.remove();
    }

    /** A block of the compile loop starts. */
    public static void beginBlock(BlockPos pos) {
        Recorder r = ACTIVE.get();
        if (r != null) r.beginBlock((pos.getX() & 15) | (pos.getY() & 15) << 4 | (pos.getZ() & 15) << 8);
    }

    /** A layer builder was fetched or created for the current block. */
    public static void track(BufferBuilder builder) {
        Recorder r = ACTIVE.get();
        if (r != null && builder != null) r.track(builder);
    }

    /** The ranges of {@code builder}'s mesh, just before it is built; null when not recorded. */
    @Nullable
    public static Ranges finish(BufferBuilder builder) {
        Recorder r = ACTIVE.get();
        return r == null ? null : r.finish(builder);
    }

    private static final class Recorder {
        final IdentityHashMap<BufferBuilder, Layer> layers = new IdentityHashMap<>(4);
        int block = -1;

        void beginBlock(int index) {
            closeBlock();
            block = index;
            for (Layer layer : layers.values()) layer.mark = layer.vertices();
        }

        void track(BufferBuilder builder) {
            if (!layers.containsKey(builder)) {
                Layer layer = new Layer(builder);
                layer.mark = layer.vertices();
                layers.put(builder, layer);
            }
        }

        void closeBlock() {
            if (block < 0) return;
            for (Layer layer : layers.values()) {
                int v = layer.vertices();
                if (v > layer.mark) layer.add(block, layer.mark, v - layer.mark);
                layer.mark = v;
            }
            block = -1;
        }

        @Nullable
        Ranges finish(BufferBuilder builder) {
            closeBlock();
            Layer layer = layers.get(builder);
            if (layer == null || layer.broken || layer.n == 0) return null;
            return new Ranges(layer.n, Arrays.copyOf(layer.block, layer.n), Arrays.copyOf(layer.first, layer.n),
                Arrays.copyOf(layer.quads, layer.n), VERSIONS.incrementAndGet());
        }
    }

    private static final class Layer {
        final BufferBuilder builder;
        int mark;
        int n;
        boolean broken;
        short[] block = new short[64];
        int[] first = new int[64];
        int[] quads = new int[64];

        Layer(BufferBuilder builder) {
            this.builder = builder;
        }

        int vertices() {
            return ((BufferBuilderAccessor) builder).ipl$getVertices();
        }

        void add(int index, int firstVertex, int vertexCount) {
            if ((firstVertex & 3) != 0 || (vertexCount & 3) != 0) {
                broken = true;
                return;
            }
            if (n == block.length) {
                block = Arrays.copyOf(block, n * 2);
                first = Arrays.copyOf(first, n * 2);
                quads = Arrays.copyOf(quads, n * 2);
            }
            block[n] = (short) index;
            first[n] = firstVertex >> 2;
            quads[n] = vertexCount >> 2;
            n++;
        }
    }
}
