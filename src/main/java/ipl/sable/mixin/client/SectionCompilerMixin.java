package ipl.sable.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.dynamic_directional_shading.ModelBlockRendererCacheExtension;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import ipl.sable.client.IplDimSwitchGuard;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Затенение граней секции саб-уровня решается по уровню самой секции.
 *
 * <p>Русский комментарий (почему): Sable перед компиляцией секции ставит флаг «это саб-уровень»
 * по {@code Minecraft.level}. С IP это уровень, который поток отрисовки рисует <i>в этот
 * момент</i>: при каждом проходе портала и при смене измерения он другой. Компиляция идёт в
 * рабочих потоках (а рядом с камерой — прямо в проходе другого измерения), и секция корабля
 * получала флаг чужого уровня: слоты участков уникальны на весь сервер, поэтому чужой уровень
 * отвечает «не саб-уровень». Тогда в вершины запекается обычное затенение граней, а шейдер
 * Sable поверх добавляет своё по ориентации корабля — грани темнеют вдвое, пока секцию не
 * пересоберут. Регион компиляции знает свой уровень ({@code RenderChunkRegion.level}), его и
 * спрашиваем — сразу после {@code enableCaching}, до первого блока.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Unique private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable");

    /** {@code ModelBlockRenderer.CACHE}: в рантайме его открывает AT Sable, при сборке он закрыт. */
    @Unique private static volatile ThreadLocal<?> ipl$cache;
    @Unique private static volatile boolean ipl$cacheMissing;

    @Inject(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;enableCaching()V",
            shift = At.Shift.AFTER),
        require = 0
    )
    private void ipl$shadeBySectionLevel(
        SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack pack,
        List<AddSectionGeometryEvent.AdditionalSectionRenderer> renderers,
        CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        ThreadLocal<?> cache = ipl$cache();
        if (cache == null || !(cache.get() instanceof ModelBlockRendererCacheExtension flag)) return;
        SubLevelContainer container = ((SubLevelContainerHolder) region).sable$getPlotContainer();
        boolean onSubLevel = container != null && container.getPlot(sectionPos.chunk()) != null;
        boolean wrong = flag.sable$getOnSubLevel() != onSubLevel;
        if (wrong) flag.sable$setOnSubLevel(onSubLevel);
        if (onSubLevel || wrong) {
            IplDimSwitchGuard.noteSubLevelSectionCompile(RenderSystem.isOnRenderThread(), wrong);
        }
    }

    @Unique
    private static ThreadLocal<?> ipl$cache() {
        ThreadLocal<?> cache = ipl$cache;
        if (cache != null || ipl$cacheMissing) return cache;
        try {
            Field field = ModelBlockRenderer.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            cache = (ThreadLocal<?>) field.get(null);
            ipl$cache = cache;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Без доступа остаётся поведение Sable: хуже, но не падение.
            ipl$cacheMissing = true;
            IPL$LOG.warn("[IPL-SABLE] ModelBlockRenderer.CACHE unavailable, section shading stays Sable's", e);
        }
        return cache;
    }
}
