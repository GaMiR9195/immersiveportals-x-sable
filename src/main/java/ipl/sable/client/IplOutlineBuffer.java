package ipl.sable.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** Small isolated Catnip buffer with the SAME early/default/late ordering as upstream. */
public final class IplOutlineBuffer implements SuperRenderTypeBuffer, AutoCloseable {
    private final ByteBufferBuilder[] storage = {
        new ByteBufferBuilder(1024), new ByteBufferBuilder(1024), new ByteBufferBuilder(1024)
    };
    private final MultiBufferSource.BufferSource[] phases = {
        MultiBufferSource.immediate(storage[0]), MultiBufferSource.immediate(storage[1]),
        MultiBufferSource.immediate(storage[2])
    };
    @Override public VertexConsumer getEarlyBuffer(RenderType type) { return phases[0].getBuffer(type); }
    @Override public VertexConsumer getBuffer(RenderType type) { return phases[1].getBuffer(type); }
    @Override public VertexConsumer getLateBuffer(RenderType type) { return phases[2].getBuffer(type); }
    @Override public void draw() { for (var phase : phases) phase.endBatch(); }
    @Override public void draw(RenderType type) { for (var phase : phases) phase.endBatch(type); }
    @Override public void close() { for (var builder : storage) builder.close(); }
}
