package ipl.sable.mixin.client;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import ipl.sable.render.IplSubLevelBlockEntityPass;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * На время BER-отрисовки BE пересекающего портал саб-уровня (и его проекций) Flywheel «не
 * поддерживается»: Create тогда рисует шестерни сам, а не полагается на скрытый инстансинг.
 * Флаг поднимается только внутри {@link IplSubLevelBlockEntityPass}.
 */
@Mixin(value = VisualizationManagerImpl.class, remap = false)
public abstract class IplFlywheelVisualizationSupportMixin {

    @Inject(
        method = "supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void ipl$forceVanillaBlockEntityRender(
        LevelAccessor level, CallbackInfoReturnable<Boolean> cir
    ) {
        if (IplSubLevelBlockEntityPass.isForcingVanillaRender()) cir.setReturnValue(false);
    }
}
