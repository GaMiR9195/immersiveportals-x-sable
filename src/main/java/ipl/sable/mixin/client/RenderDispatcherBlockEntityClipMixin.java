package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable's block-entity pass of sub-levels
 * ({@code VanillaSubLevelRenderDispatcher.renderBlockEntities}) goes through
 * {@link IplSubLevelBlockEntityPass#route}: one sub-level at a time, a straddling one as piece
 * passes flushed after each pass, plus the sub-level's global block entities and the ones
 * taken out of Flywheel. The original runs again from inside the route (re-entry flag).
 */
@Pseudo
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class RenderDispatcherBlockEntityClipMixin {

    @Inject(
        method = "renderBlockEntities(Ljava/lang/Iterable;Ldev/ryanhcode/sable/sublevel/render/dispatcher/SubLevelRenderDispatcher$BlockEntityRenderer;DDDF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeBlockEntities(
        Iterable<ClientSubLevel> subLevels,
        SubLevelRenderDispatcher.BlockEntityRenderer renderer,
        double camX, double camY, double camZ, float partialTick,
        CallbackInfo ci
    ) {
        if (IplSubLevelBlockEntityPass.route(
            (SubLevelRenderDispatcher) (Object) this, subLevels, renderer, camX, camY, camZ, partialTick)) {
            ci.cancel();
        }
    }
}
