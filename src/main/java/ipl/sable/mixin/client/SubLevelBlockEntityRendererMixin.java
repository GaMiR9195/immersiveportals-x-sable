package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.render.IplPieceRender;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Каждая BE саб-уровня, нарисованная через {@link VanillaSubLevelBlockEntityRenderer#renderSingleBE}.
 *
 * <p>У Sable два пути отрисовки BE саб-уровней, и оба сходятся в {@code renderSingleBE}:
 * <ol>
 *   <li>{@code VanillaSubLevelRenderDispatcher.renderBlockEntities} — по секциям чанков
 *       (см. {@link RenderDispatcherBlockEntityClipMixin});</li>
 *   <li>{@code LevelRendererMixin.sable$renderBlockEntities} — ванильный цикл
 *       {@code LevelRenderer.renderLevel}: для BE саб-уровня он зовёт {@code renderSingleBE}
 *       напрямую, мимо диспетчера.</li>
 * </ol>
 * Здесь отбрасываются BE чужого куска ({@link IplPieceRender#rejectsBlockEntity}) и уже
 * нарисованные глобальные BE; плоскости и сброс буфера держит {@code IplBlockEntityRenderScope}.
 */
@Pseudo
@Mixin(value = VanillaSubLevelBlockEntityRenderer.class, remap = false)
public abstract class SubLevelBlockEntityRendererMixin {

    /**
     * Буферы, захваченные конструктором рендерера Sable. Sable создаёт его в
     * {@code LevelRenderer.<init>} с ОСНОВНЫМИ буферами, а IP на время прохода портала
     * подменяет {@code RenderBuffers} у Minecraft и у LevelRenderer на вторичные. Без
     * переключения вершины BE из прохода портала остались бы в основном буфере и сбросились
     * бы уже во внешнем проходе — с матрицами внешней камеры («кривые копии» шестерёнок
     * и сундуков, приклеенные к игроку со смещением).
     */
    @Shadow @Final @Mutable
    private RenderBuffers renderBuffers;

    @Inject(
        method = "renderSingleBE(Lnet/minecraft/world/level/block/entity/BlockEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FDDD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$useCurrentBuffers(
        BlockEntity be, PoseStack pose, float partialTick, double x, double y, double z, CallbackInfo ci
    ) {
        // Вызов из ванильного общего цикла для BE, которую маршрут IplSubLevelBlockEntityPass уже
        // нарисовал (или которая принадлежит другому измерению) — пропускаем.
        if (IplSubLevelBlockEntityPass.shouldSkipGlobalLoopCall(be) || IplPieceRender.rejectsBlockEntity(be)) {
            ci.cancel();
            return;
        }
        IplSubLevelBlockEntityPass.noteRendered(be);
        this.renderBuffers = Minecraft.getInstance().renderBuffers();
    }

    /**
     * Проекция: матрица куска уже в PoseStack. Внутри BER renderPose() должен вернуть НАСТОЯЩУЮ
     * позу — верёвки Simulated переводят мировые точки в локальные через
     * renderPose().transformPositionInverse(); с подменённой позой верёвка оказалась бы в
     * исходных мировых координатах внутри чужого измерения («копии верёвок вдали»).
     */
    @WrapOperation(
        method = "renderSingleBE",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
        )
    )
    private void ipl$renderWithRealPose(
        BlockEntityRenderDispatcher dispatcher,
        BlockEntity be,
        float partialTick,
        PoseStack pose,
        MultiBufferSource source,
        Operation<Void> original
    ) {
        ClientSubLevel sub = IplStraddleRenderState.isActive() ? Sable.HELPER.getContainingClient(be) : null;
        boolean projected = sub != null && IplStraddleRenderState.isActiveFor(sub);
        if (projected) IplStraddleRenderState.suspendPose();
        try {
            original.call(dispatcher, be, partialTick, pose, source);
        } finally {
            if (projected) IplStraddleRenderState.resumePose();
        }
    }
}
