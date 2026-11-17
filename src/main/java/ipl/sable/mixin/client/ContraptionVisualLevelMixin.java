package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import ipl.sable.render.IplContraptionRenderPoses;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Create's contraption visuals read Sable's sub-level poses while updating their transforms;
 * they read them from their own level ({@link IplContraptionRenderPoses}). Each target has one
 * of the two methods: {@code setEmbeddingMatrices} in {@code ContraptionVisual}, {@code animate}
 * in {@code CarriageContraptionVisual}.
 */
@Pseudo
@Mixin(
	targets = {
		"com.simibubi.create.content.contraptions.render.ContraptionVisual",
		"com.simibubi.create.content.trains.entity.CarriageContraptionVisual"
	},
	remap = false
)
public abstract class ContraptionVisualLevelMixin extends AbstractVisual {

	private ContraptionVisualLevelMixin(VisualizationContext ctx, Level level, float partialTick) {
		super(ctx, level, partialTick);
	}

	@WrapMethod(method = "setEmbeddingMatrices(F)V")
	private void ipl$setEmbeddingMatrices(float partialTick, Operation<Void> original) {
		IplContraptionRenderPoses.withVisualLevel(this.level, () -> original.call(partialTick));
	}

	@WrapMethod(method = "animate(F)V")
	private void ipl$animate(float partialTick, Operation<Void> original) {
		IplContraptionRenderPoses.withVisualLevel(this.level, () -> original.call(partialTick));
	}
}
