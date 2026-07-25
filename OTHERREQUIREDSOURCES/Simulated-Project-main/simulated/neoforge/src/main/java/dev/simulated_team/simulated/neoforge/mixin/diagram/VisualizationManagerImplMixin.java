package dev.simulated_team.simulated.neoforge.mixin.diagram;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.simulated_team.simulated.mixin_interface.diagram.VisualizationManagerExtension;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VisualizationManagerImpl.class)
public class VisualizationManagerImplMixin implements VisualizationManagerExtension {

    @Unique
    private static boolean sable$drawingDiagram = false;

    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
    private static void simulated$supportsVisualization(final LevelAccessor level, final CallbackInfoReturnable<Boolean> cir) {
        if (sable$drawingDiagram) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public void sable$setDrawingDiagram(final boolean drawing) {
        sable$drawingDiagram = drawing;
    }
}
