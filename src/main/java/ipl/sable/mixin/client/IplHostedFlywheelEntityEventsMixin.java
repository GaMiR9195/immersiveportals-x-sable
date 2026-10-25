package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler;
import ipl.sable.client.IplHostedFlywheelVisuals;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(value = VisualizationEventHandler.class, remap = false)
public abstract class IplHostedFlywheelEntityEventsMixin {
    @WrapOperation(method = {"onEntityJoinLevel", "onEntityLeaveLevel"}, at = @At(value = "INVOKE",
        target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;get(Lnet/minecraft/world/level/LevelAccessor;)Ldev/engine_room/flywheel/api/visualization/VisualizationManager;"), require = 2)
    private static VisualizationManager ipl$entityView(LevelAccessor level, Operation<VisualizationManager> original,
            @Local(argsOnly = true) Entity entity) {
        return original.call(IplHostedFlywheelVisuals.entityView((Level)level,entity));
    }
}
