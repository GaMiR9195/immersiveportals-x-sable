package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderer;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import ipl.sable.render.IplPerLevelSubLevelRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Under Sodium, Sable's renderer is created as {@link IplPerLevelSubLevelRenderDispatcher}
 * instead of the reach-around dispatcher that compiles all levels through one section dispatcher.
 * The vanilla (non-Sodium) path already compiles through each level's own {@code LevelRenderer}.
 */
@Mixin(value = SubLevelRenderer.class, remap = false)
public abstract class IplSubLevelRendererMixin {

    @WrapOperation(
        method = "getDispatcher",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/render/SubLevelRenderer$SelectedRenderer;create()Ldev/ryanhcode/sable/sublevel/render/dispatcher/SubLevelRenderDispatcher;"
        ),
        require = 1
    )
    private static SubLevelRenderDispatcher ipl$perLevelSections(
        SubLevelRenderer.SelectedRenderer renderer, Operation<SubLevelRenderDispatcher> original
    ) {
        return renderer == SubLevelRenderer.SelectedRenderer.SODIUM_REACHAROUND
            ? new IplPerLevelSubLevelRenderDispatcher()
            : original.call(renderer);
    }
}
