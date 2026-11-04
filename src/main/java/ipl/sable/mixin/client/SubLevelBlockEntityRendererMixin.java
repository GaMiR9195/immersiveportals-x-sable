package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.render.IplBlockEntityRenderScope;
import ipl.sable.render.IplClipPortalFinder;
import ipl.sable.render.IplClipUniformPatcher;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Плоскости отсечения для BE саб-уровня, нарисованных через
 * {@link VanillaSubLevelBlockEntityRenderer#renderSingleBE}.
 *
 * <p>У Sable два пути отрисовки BE саб-уровней, и оба сходятся в {@code renderSingleBE}:
 * <ol>
 *   <li>{@code VanillaSubLevelRenderDispatcher.renderBlockEntities} — по секциям чанков
 *       (см. также {@link RenderDispatcherBlockEntityClipMixin});</li>
 *   <li>{@code LevelRendererMixin.sable$renderBlockEntities} — ванильный цикл
 *       {@code LevelRenderer.renderLevel}: для BE саб-уровня он зовёт {@code renderSingleBE}
 *       напрямую, мимо диспетчера. Так рисуются, например, шестерни Create
 *       ({@code KineticBlockEntityRenderer}).</li>
 * </ol>
 * Если сработали обе скобки, они вкладываются безвредно: установка плоскостей, включение
 * и сброс идемпотентны.
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
        if (IplSubLevelBlockEntityPass.shouldSkipGlobalLoopCall(be)) {
            ci.cancel();
            return;
        }
        IplSubLevelBlockEntityPass.noteRendered(be);
        this.renderBuffers = Minecraft.getInstance().renderBuffers();
    }

    /**
     * Wrap the inner {@code BlockEntityRenderDispatcher.render(...)} call so
     * we can bracket exactly the BE's render call with patch/clear. Going
     * through @WrapOperation instead of HEAD/RETURN inject pairs guarantees
     * the cleanup runs even if the inner render throws.
     */
    @WrapOperation(
        method = "renderSingleBE",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
        )
    )
    private void ipl$wrapBERender(
        BlockEntityRenderDispatcher dispatcher,
        BlockEntity be,
        float partialTick,
        PoseStack pose,
        MultiBufferSource source,
        Operation<Void> original
    ) {
        ClientSubLevel sub = Sable.HELPER.getContainingClient(be);
        if (sub == null) {
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        // Проекция: матрица куска уже в PoseStack. Внутри BER renderPose() должен вернуть
        // НАСТОЯЩУЮ позу — верёвки Simulated переводят мировые точки в локальные через
        // renderPose().transformPositionInverse(); с подменённой позой верёвка оказалась бы в
        // исходных мировых координатах внутри чужого измерения («копии верёвок вдали»).
        boolean projected = IplStraddleRenderState.isActiveFor(sub);
        if (projected) IplStraddleRenderState.suspendPose();
        try {
            ipl$renderBracketed(sub, dispatcher, be, partialTick, pose, source, original);
        } finally {
            if (projected) IplStraddleRenderState.resumePose();
        }
    }

    @Unique
    private static void ipl$renderBracketed(
        ClientSubLevel sub,
        BlockEntityRenderDispatcher dispatcher,
        BlockEntity be,
        float partialTick,
        PoseStack pose,
        MultiBufferSource source,
        Operation<Void> original
    ) {
        IplClipPortalFinder.ClipDecision decision =
            IplClipPortalFinder.findStraddlingPortalPlane(sub);
        // Clip emitted geometry, not the block centre: a sign/chest may cross the plane while
        // its centre is behind it. Inside IplBlockEntityRenderScope the planes are already
        // installed and stay active through BufferSource.endBatch(), when deferred BE vertices
        // actually draw.
        if (decision == null || decision.planes().isEmpty()
            || IplBlockEntityRenderScope.isActiveFor(sub)) {
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        // Fallback for a BE drawn outside IplSubLevelBlockEntityPass: the planes bracket only
        // the call while the vertices are deferred to endBatch(). Flushing the buffer here is
        // not an option — it corrupts catnip's shared shade-separation buffer ("Not building!"
        // crash) — which is why the main path always goes through IplBlockEntityRenderScope.
        IplClipUniformPatcher.patchForSubLevel(sub, decision.planes());

        try {
            original.call(dispatcher, be, partialTick, pose, source);
        } finally {
            IplClipUniformPatcher.disableSlots();
            IplClipUniformPatcher.clearAndUpload();
        }
    }
}
