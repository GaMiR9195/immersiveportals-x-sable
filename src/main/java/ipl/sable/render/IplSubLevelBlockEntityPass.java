package ipl.sable.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.BlockEntityRenderDispatcherExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.mixin.client.IplRenderSectionGlobalsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Единая точка входа для блок-сущностей саб-уровней
 * ({@code VanillaSubLevelRenderDispatcher.renderBlockEntities}, HEAD-перехват в
 * {@code SableSubLevelBlockEntityClipMixin}).
 *
 * <p><b>Почему так, а не скобками вокруг каждой BE.</b> BER ничего не рисует сам — он лишь
 * складывает вершины в {@code BufferSource}, а шейдер выполняется при {@code endBatch()}.
 * Скобка «поставить плоскость → BER → снять плоскость» заканчивалась ДО реальной отрисовки,
 * поэтому шестерни/сундуки/верёвки на пересекающем портал корабле резались чем попало (или
 * не резались). Теперь каждый пересекающий портал саб-уровень рисует свои BE внутри
 * {@link SubLevelBlockEntityRenderScope#renderAndFlush}: плоскости ставятся один раз, весь
 * проход BE саб-уровня выполняется, и только ПОСЛЕ него буфер сбрасывается — вне любого BER
 * (сброс внутри BER ломал общий буфер catnip, это и был старый краш «Not building!»).
 *
 * <p><b>Что рисуется сверх оригинала Sable</b> (одной и той же матрицей саб-уровня — для
 * проекции уже отображённой — и «локальной» камерой, как у Sable):
 * <ul>
 *   <li>{@code RenderSection.globalBlockEntities} — верёвки, лебёдки и прочие BE с бесконечной
 *       рамкой. Раньше их рисовал только ванильный общий цикл, причём настоящей позой и без
 *       плоскостей куска: в проекции верёвки оставались «на старом месте», а под Sodium в тот
 *       же цикл попадали верёвки саб-уровней ЧУЖИХ измерений. Ванильный цикл теперь такие BE
 *       пропускает ({@link #shouldSkipGlobalLoopCall}).</li>
 *   <li>BE, которые обычно визуализирует Flywheel, — только пока саб-уровень пересекает
 *       портал (или рисуется как проекция). Их окружение Flywheel в это время схлопнуто
 *       ({@link IplFlywheelFallback}), а BER вызывается с «выключенным» Flywheel
 *       ({@link #isForcingVanillaRender()}): так шестерни режутся плоскостью портала и
 *       переходят в кусок-проекцию, а не висят целиком на исходном месте.</li>
 * </ul>
 * Если наш перехват по какой-то причине не применился, всё остаётся ванильным.
 */
public final class IplSubLevelBlockEntityPass {

    private IplSubLevelBlockEntityPass() {}

    /** Flywheel — необязательная зависимость; класс фолбэка трогаем только при её наличии. */
    private static final class Flywheel {
        static final boolean LOADED = detect();

        private static boolean detect() {
            try {
                ModList list = ModList.get();
                return list != null && list.isLoaded("flywheel");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** &gt; 0, пока выполняется диспетчер Sable (наш маршрут или его оригинал). Только render thread. */
    private static int dispatcherDepth;
    /** true на время нашего повторного вызова оригинального метода — перехват не должен сработать снова. */
    private static boolean redispatching;
    /** Маршрут хотя бы раз отработал — значит, глобальные BE рисуем мы, а не ванильный цикл. */
    private static boolean routeSeen;
    /** &gt; 0 — BER должны рисовать всё сами (см. {@code IplFlywheelVisualizationSupportMixin}). */
    private static int forceVanilla;
    /** BE, уже нарисованные в текущем «теле» саб-уровня (чтобы не рисовать дважды). */
    private static Set<BlockEntity> currentRecord;
    /**
     * Глобальные BE, нарисованные нами в текущем проходе {@code LevelRenderer.renderLevel}.
     * Стек — потому что проходы порталов IP вложены во внешний проход.
     */
    private static final ArrayDeque<Set<BlockEntity>> DRAWN_GLOBALS = new ArrayDeque<>();

    private static Set<BlockEntity> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** HEAD {@code LevelRenderer.renderLevel}. */
    public static void beginLevelPass() {
        // Защита от разбалансировки (исключение посреди прохода): стек не должен расти вечно.
        if (DRAWN_GLOBALS.size() > 32) DRAWN_GLOBALS.clear();
        DRAWN_GLOBALS.addLast(newIdentitySet());
    }

    /** RETURN {@code LevelRenderer.renderLevel}. */
    public static void endLevelPass() {
        DRAWN_GLOBALS.pollLast();
    }

    public static boolean insideDispatcher() {
        return dispatcherDepth > 0;
    }

    /**
     * Только для render thread: секции компилируются и в рабочих потоках, и там
     * {@code VisualizationHelper.tryAddBlockEntity} тоже спрашивает
     * {@code supportsVisualization} — «выключенный» на миг Flywheel оставил бы BE в списке
     * секции, и сундук рисовался бы дважды (BER + визуал), пока секция не перекомпилируется.
     */
    public static boolean isForcingVanillaRender() {
        return forceVanilla > 0 && RenderSystem.isOnRenderThread();
    }

    /** HEAD {@code renderSingleBE} (после проверки пропуска): BE нарисована в текущем теле. */
    public static void noteRendered(BlockEntity be) {
        Set<BlockEntity> record = currentRecord;
        if (record != null && be != null) record.add(be);
    }

    /**
     * HEAD {@code renderBlockEntities}. Возвращает true, если проход выполнен здесь и оригинал
     * нужно отменить; false — если это наш собственный повторный вызов оригинала.
     */
    public static boolean route(
        SubLevelRenderDispatcher dispatcher, Iterable<ClientSubLevel> subLevels,
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        double camX, double camY, double camZ, float partialTick
    ) {
        if (redispatching) return false;
        routeSeen = true;
        dispatcherDepth++;
        try {
            for (ClientSubLevel sub : subLevels) {
                if (sub == null) continue;
                renderOne(dispatcher, sub, renderer, camX, camY, camZ, partialTick);
            }
        } finally {
            dispatcherDepth--;
        }
        return true;
    }

    private static void renderOne(
        SubLevelRenderDispatcher dispatcher, ClientSubLevel sub,
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        double camX, double camY, double camZ, float partialTick
    ) {
        // «Преемник» перехода скрыт, пока призрак не передаст ему отрисовку: его секции уже не
        // рисуются (IplSubLevelRenderVisibility), а BE раньше рисовались — вторая копия
        // сундуков/шестерён рядом с призраком.
        if (IplParentDimSync.isSuppressedSuccessor(sub)) return;

        // Проекцию (IplStraddleRenderState) и уже открытую область держит вызывающий код —
        // он сам ставит плоскости куска и сам сбрасывает буфер после прохода.
        if (IplStraddleRenderState.isActiveFor(sub) || SubLevelBlockEntityRenderScope.isActiveFor(sub)) {
            renderBody(dispatcher, sub, renderer, camX, camY, camZ, partialTick, true);
            return;
        }
        if (SourceClipPortalFinder.findStraddlingPortalPlane(sub) == null) {
            renderBody(dispatcher, sub, renderer, camX, camY, camZ, partialTick, false);
            return;
        }
        // Пересекающий портал саб-уровень в своём (нативном) проходе: одна область на весь
        // проход его BE, сброс — после. Буфер берём текущий: во время прохода портала IP
        // подменяет RenderBuffers у Minecraft и у LevelRenderer на вторичные.
        SubLevelBlockEntityRenderScope.renderAndFlush(
            sub, Minecraft.getInstance().renderBuffers().bufferSource(),
            () -> renderBody(dispatcher, sub, renderer, camX, camY, camZ, partialTick, true));
    }

    /**
     * Оригинальный проход Sable для одного саб-уровня + глобальные BE (+ BE Flywheel, если
     * {@code cut}: саб-уровень режется порталом или рисуется как проекция).
     */
    private static void renderBody(
        SubLevelRenderDispatcher dispatcher, ClientSubLevel sub,
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        double camX, double camY, double camZ, float partialTick, boolean cut
    ) {
        boolean flywheelFallback = cut && Flywheel.LOADED;
        Set<BlockEntity> previousRecord = currentRecord;
        Set<BlockEntity> record = newIdentitySet();
        currentRecord = record;
        if (flywheelFallback) forceVanilla++;
        try {
            callOriginal(dispatcher, sub, renderer, camX, camY, camZ, partialTick);
            List<BlockEntity> extras = collectExtras(sub, flywheelFallback, record);
            if (!extras.isEmpty()) renderExtras(sub, renderer, extras, camX, camY, camZ, partialTick);
        } finally {
            if (flywheelFallback) forceVanilla--;
            currentRecord = previousRecord;
        }
    }

    private static void callOriginal(
        SubLevelRenderDispatcher dispatcher, ClientSubLevel sub,
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        double camX, double camY, double camZ, float partialTick
    ) {
        boolean previous = redispatching;
        redispatching = true;
        try {
            dispatcher.renderBlockEntities(List.of(sub), renderer, camX, camY, camZ, partialTick);
        } finally {
            redispatching = previous;
        }
    }

    /** Глобальные BE саб-уровня и (при фолбэке) BE Flywheel, ещё не нарисованные в этом теле. */
    private static List<BlockEntity> collectExtras(
        ClientSubLevel sub, boolean flywheelFallback, Set<BlockEntity> alreadyDrawn
    ) {
        List<BlockEntity> out = null;
        Set<BlockEntity> drawnGlobals = DRAWN_GLOBALS.peekLast();
        if (sub.getRenderData() instanceof VanillaChunkedSubLevelRenderData chunks) {
            for (SectionRenderDispatcher.RenderSection section : chunks.allRenderSections()) {
                Set<BlockEntity> set = ((IplRenderSectionGlobalsAccessor) section).ipl$getGlobalBlockEntities();
                synchronized (set) {
                    if (set.isEmpty()) continue;
                    for (BlockEntity be : set) {
                        if (drawnGlobals != null) drawnGlobals.add(be);
                        if (alreadyDrawn.contains(be)) continue;
                        if (out == null) out = new ArrayList<>();
                        out.add(be);
                    }
                }
            }
        }
        if (flywheelFallback) {
            List<BlockEntity> visualized = IplFlywheelFallback.visualizedBlockEntities(sub);
            if (!visualized.isEmpty()) {
                Set<BlockEntity> seen = newIdentitySet();
                if (out != null) seen.addAll(out);
                for (BlockEntity be : visualized) {
                    if (be == null || alreadyDrawn.contains(be) || !seen.add(be)) continue;
                    if (out == null) out = new ArrayList<>();
                    out.add(be);
                }
            }
        }
        return out == null ? List.of() : out;
    }

    /** Есть ли у саб-уровня BE, которых нет в посекционных списках (ранний выход «нечего рисовать»). */
    public static boolean hasExtraBlockEntities(ClientSubLevel sub) {
        if (hasGlobalBlockEntities(sub)) return true;
        return Flywheel.LOADED && IplFlywheelFallback.hasVisualized(sub);
    }

    /** Есть ли у саб-уровня глобальные BE. */
    public static boolean hasGlobalBlockEntities(ClientSubLevel sub) {
        if (!(sub.getRenderData() instanceof VanillaChunkedSubLevelRenderData chunks)) return false;
        for (SectionRenderDispatcher.RenderSection section : chunks.allRenderSections()) {
            Set<BlockEntity> globals = ((IplRenderSectionGlobalsAccessor) section).ipl$getGlobalBlockEntities();
            synchronized (globals) {
                if (!globals.isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * Та же математика, что у Sable в {@code VanillaSubLevelRenderDispatcher.renderBlockEntities}
     * и в {@code LevelRendererMixin.sable$renderBlockEntities}: матрица саб-уровня (для проекции —
     * уже отображённая), «локальная» камера для {@code shouldRender}/LOD, смещение от точки
     * вращения. Отсечение по фрустуму не делаем: таких BE единицы, а рамки верёвок бесконечные
     * (преобразование бесконечной рамки позой даёт мусор).
     */
    private static void renderExtras(
        ClientSubLevel sub, SubLevelRenderDispatcher.BlockEntityRenderer renderer, List<BlockEntity> extras,
        double camX, double camY, double camZ, float partialTick
    ) {
        Vector3dc rotationPoint = sub.renderPose().rotationPoint();
        Matrix4f transformation = sub.getRenderData().getTransformation(camX, camY, camZ, new Matrix4f());
        Vector3f localCamera = transformation.invert(new Matrix4f()).transformPosition(new Vector3f());
        BlockEntityRenderDispatcherExtension extension =
            (BlockEntityRenderDispatcherExtension) renderer.getBlockEntityRenderDispatcher();
        extension.sable$setCameraPosition(new Vec3(
            localCamera.x + rotationPoint.x(), localCamera.y + rotationPoint.y(), localCamera.z + rotationPoint.z()));
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(transformation);
        try {
            for (BlockEntity be : extras) {
                if (be.isRemoved()) continue;
                renderer.renderSingleBE(be, poseStack, partialTick,
                    rotationPoint.x(), rotationPoint.y(), rotationPoint.z());
            }
        } finally {
            extension.sable$setCameraPosition(null);
        }
    }

    /**
     * HEAD {@code VanillaSubLevelBlockEntityRenderer.renderSingleBE}: вызов из ванильного общего
     * цикла (вне диспетчера) пропускаем, если эту BE мы уже нарисовали в этом проходе, если она
     * принадлежит другому измерению (под Sodium общий набор содержит BE всех измерений) или
     * скрытому «преемнику» перехода.
     */
    public static boolean shouldSkipGlobalLoopCall(BlockEntity be) {
        if (!routeSeen || dispatcherDepth > 0 || be == null) return false;
        Set<BlockEntity> drawn = DRAWN_GLOBALS.peekLast();
        if (drawn != null && drawn.contains(be)) return true;
        Level beLevel = be.getLevel();
        if (beLevel != null && beLevel != Minecraft.getInstance().level) return true;
        if (IplParentDimSync.hasGhosts()) {
            ClientSubLevel sub = Sable.HELPER.getContainingClient(be);
            return sub != null && IplParentDimSync.isSuppressedSuccessor(sub);
        }
        return false;
    }
}
