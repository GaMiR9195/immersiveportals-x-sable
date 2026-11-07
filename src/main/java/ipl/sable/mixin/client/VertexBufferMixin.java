package ipl.sable.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import ipl.sable.render.IplSectionRanges;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Section buffers keep their mesh's per-block ranges for piece draws ({@code IplPieceRender}),
 * and, for sorted translucent meshes, the quad order of the last uploaded index buffer.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin implements IplSectionRanges.Holder, IplSectionRanges.BufferAccess {

    @Shadow private int indexBufferId;
    @Shadow private int indexCount;
    @Shadow @Nullable private RenderSystem.AutoStorageIndexBuffer sequentialIndices;
    @Shadow private VertexFormat.Mode mode;
    @Shadow private VertexFormat.IndexType indexType;

    @Unique @Nullable private IplSectionRanges.Ranges ipl$ranges;
    @Unique @Nullable private Object ipl$plan;
    @Unique @Nullable private int[] ipl$quadOrder;

    @Inject(method = "upload", at = @At("HEAD"))
    private void ipl$takeRanges(MeshData mesh, CallbackInfo ci) {
        this.ipl$ranges = mesh == null ? null : ((IplSectionRanges.Holder) mesh).ipl$ranges();
        this.ipl$plan = null;
    }

    /** The mesh's own index buffer (sorted translucency at compile time); null = sequential. */
    @Inject(method = "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/MeshData$DrawState;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer;",
        at = @At("HEAD"))
    private void ipl$takeQuadOrder(MeshData.DrawState state, @Nullable ByteBuffer indices,
                                   CallbackInfoReturnable<RenderSystem.AutoStorageIndexBuffer> cir) {
        this.ipl$quadOrder = ipl$ranges == null || indices == null ? null : IplSectionRanges.quadOrder(indices, state.indexType());
    }

    /** A translucency resort replaced the index buffer. */
    @Inject(method = "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V", at = @At("HEAD"))
    private void ipl$takeResortedQuadOrder(ByteBufferBuilder.Result result, CallbackInfo ci) {
        this.ipl$quadOrder = ipl$ranges == null || indexType == null ? null : IplSectionRanges.quadOrder(result.byteBuffer(), indexType);
    }

    @Override
    public int @Nullable [] ipl$quadOrder() {
        return ipl$quadOrder;
    }

    @Override
    public IplSectionRanges.@Nullable Ranges ipl$ranges() {
        return ipl$ranges;
    }

    @Override
    public void ipl$setRanges(IplSectionRanges.@Nullable Ranges ranges) {
        this.ipl$ranges = ranges;
        this.ipl$plan = null;
    }

    @Override
    public RenderSystem.@Nullable AutoStorageIndexBuffer ipl$sequential() {
        return sequentialIndices;
    }

    @Override
    public int ipl$indexBufferId() {
        return indexBufferId;
    }

    @Override
    public int ipl$indexCount() {
        return indexCount;
    }

    @Override
    public VertexFormat.@Nullable Mode ipl$mode() {
        return mode;
    }

    @Override
    public @Nullable Object ipl$plan() {
        return ipl$plan;
    }

    @Override
    public void ipl$setPlan(@Nullable Object plan) {
        this.ipl$plan = plan;
    }
}
