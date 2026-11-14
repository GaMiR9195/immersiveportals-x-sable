package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import ipl.sable.render.clip.IplClipPlanes;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Veil post-processing draws full-screen passes: the world cuts are suspended while it runs. */
@Pseudo
@Mixin(value = PostProcessingManager.class, remap = false)
public abstract class VeilPostProcessingMixin {

	@WrapMethod(method = "runDefaultPipeline")
	private void ipl$unclippedDefaultPipeline(
		@Nullable VeilRenderLevelStageEvent.Stage stage, Operation<Void> original
	) {
		IplClipPlanes.suppressed(() -> original.call(stage));
	}

	@WrapMethod(method = "runPipeline(Lfoundry/veil/api/client/render/post/PostPipeline;Z)V")
	private void ipl$unclippedPipeline(PostPipeline pipeline, boolean resolvePost, Operation<Void> original) {
		IplClipPlanes.suppressed(() -> original.call(pipeline, resolvePost));
	}
}
