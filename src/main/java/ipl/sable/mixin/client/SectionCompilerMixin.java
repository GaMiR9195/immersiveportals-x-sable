package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.dynamic_directional_shading.ModelBlockRendererCacheExtension;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import ipl.sable.render.IplSectionRanges;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Section compile of sub-level plots.
 *
 * <p>Face shading is decided by the section's own level. Sable sets its "sub-level section"
 * flag at the start of {@code compile} from {@code Minecraft.level}; with IP that is whichever
 * level is being drawn right now (every portal pass, every dimension change), and sections
 * compile on worker threads, so a ship section could get a foreign level's answer: vanilla face
 * shading baked into the vertices plus Sable's own in the shader, faces twice as dark until the
 * next rebuild. The compile region knows its level; the flag is set from it, after
 * {@code enableCaching} (after Sable's HEAD injection, before the first block).
 *
 * <p>Sub-level sections also record per-block vertex ranges ({@link IplSectionRanges}), so a
 * piece of a body crossing a portal can be drawn from the same buffers without clipping.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Inject(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;enableCaching()V",
            shift = At.Shift.AFTER)
    )
    private void ipl$shadeBySectionLevel(
        SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack pack,
        List<AddSectionGeometryEvent.AdditionalSectionRenderer> renderers,
        CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        SubLevelContainer container = ((SubLevelContainerHolder) region).sable$getPlotContainer();
        boolean onSubLevel = container != null && container.getPlot(sectionPos.chunk()) != null;
        ((ModelBlockRendererCacheExtension) ModelBlockRenderer.CACHE.get()).sable$setOnSubLevel(onSubLevel);
        IplSectionRanges.begin(onSubLevel);
    }

    /** The first block-state read of each loop iteration starts a new block. */
    @WrapOperation(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 0)
    )
    private BlockState ipl$beginBlock(RenderChunkRegion region, BlockPos pos, Operation<BlockState> original) {
        IplSectionRanges.beginBlock(pos);
        return original.call(region, pos);
    }

    @Inject(method = "getOrBeginLayer", at = @At("RETURN"))
    private void ipl$trackLayer(
        Map<RenderType, BufferBuilder> layers, SectionBufferBuilderPack pack, RenderType type,
        CallbackInfoReturnable<BufferBuilder> cir
    ) {
        IplSectionRanges.track(cir.getReturnValue());
    }

    @WrapOperation(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;build()Lcom/mojang/blaze3d/vertex/MeshData;")
    )
    private MeshData ipl$attachRanges(BufferBuilder builder, Operation<MeshData> original) {
        IplSectionRanges.Ranges ranges = IplSectionRanges.finish(builder);
        MeshData mesh = original.call(builder);
        if (mesh != null) ((IplSectionRanges.Holder) mesh).ipl$setRanges(ranges);
        return mesh;
    }

    @Inject(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At("RETURN")
    )
    private void ipl$endRanges(CallbackInfoReturnable<SectionCompiler.Results> cir) {
        IplSectionRanges.end();
    }
}
