package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
/** Route lifecycle events by their actual object, never substitute the hosting Level. */
@Pseudo
@Mixin(value = VisualizationHelper.class, remap = false)
public abstract class IplHostedFlywheelApiMixin {
    @WrapOperation(method = {"queueUpdate(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", "tryAddBlockEntity"},
        at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;get(Lnet/minecraft/world/level/LevelAccessor;)Ldev/engine_room/flywheel/api/visualization/VisualizationManager;"), require = 1)
    private static VisualizationManager ipl$beManager(LevelAccessor level, Operation<VisualizationManager> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) BlockEntity be) {
        var sub = dev.ryanhcode.sable.Sable.HELPER.getContaining(be);
        return original.call(sub != null && IplDimAgnostic.isHosted(sub) ? IplDimAgnostic.getParentLevel(sub) : level);
    }
    @WrapOperation(method = "queueUpdate(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;get(Lnet/minecraft/world/level/LevelAccessor;)Ldev/engine_room/flywheel/api/visualization/VisualizationManager;"), require = 1)
    private static VisualizationManager ipl$entityManager(LevelAccessor level, Operation<VisualizationManager> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Entity entity) {
        var sub = dev.ryanhcode.sable.Sable.HELPER.getContaining(entity);
        return original.call(sub != null && IplDimAgnostic.isHosted(sub) ? IplDimAgnostic.getParentLevel(sub) : level);
    }
}
