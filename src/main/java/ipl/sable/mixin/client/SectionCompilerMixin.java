package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.dynamic_directional_shading.ModelBlockRendererCacheExtension;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Затенение граней секции саб-уровня решается по уровню самой секции.
 *
 * <p>Sable в начале {@code compile} ставит флаг «секция саб-уровня» по {@code Minecraft.level}.
 * С IP это уровень, который рисуется в данный момент: при каждом проходе портала и при смене
 * измерения он другой. Секции компилируются в рабочих потоках (рядом с камерой — прямо в проходе
 * другого измерения), и по {@code Minecraft.level} секция корабля получила бы флаг чужого уровня.
 * Слоты участков уникальны на весь сервер, поэтому чужой уровень отвечает «не саб-уровень»: в
 * вершины запеклось бы обычное затенение граней, шейдер Sable добавил бы своё, и грани темнели бы
 * вдвое до пересборки секции. Регион компиляции знает свой уровень, флаг ставится по нему — после
 * {@code enableCaching}, то есть после инъекции Sable в начало метода и до первого блока.
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
    }
}
