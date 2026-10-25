package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import ipl.sable.client.IplClientVisualViewContext;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
@Pseudo
@Mixin(value = VisualizationManager.class, remap = false)
public interface IplFlywheelScopedManagerMixin {
    @WrapMethod(method = "get")
    private static VisualizationManager ipl$objectView(LevelAccessor source,Operation<VisualizationManager> original) {
        return original.call(IplClientVisualViewContext.resolve(source));
    }

    @WrapMethod(method = "getOrThrow")
    private static VisualizationManager ipl$objectViewOrThrow(LevelAccessor source, Operation<VisualizationManager> original) {
        return original.call(IplClientVisualViewContext.resolve(source));
    }

    /** Same object-scoped view for the support query renderers use to skip visualized parts. */
    @WrapMethod(method = "supportsVisualization")
    private static boolean ipl$objectSupport(LevelAccessor source, Operation<Boolean> original) {
        return original.call(IplClientVisualViewContext.resolve(source));
    }
}
