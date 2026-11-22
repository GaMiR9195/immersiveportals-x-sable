package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.render.route.IplPortalModels;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The entity draw funnel: a contraption or another entity carried by a body that passes a portal,
 * or that hangs beyond one, is drawn by {@link IplPortalModels} in the frames its parts are in.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEntityRoutingMixin {

	@Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
	private void ipl$routeThroughOpenings(Entity entity, double cameraX, double cameraY, double cameraZ,
										  float partialTick, PoseStack poseStack, MultiBufferSource buffers,
										  CallbackInfo ci) {
		if (IplPortalModels.claim(entity, buffers)) ci.cancel();
	}
}
