package qouteall.imm_ptl.core.compat.mixin.entityculling;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.EntityCullingCompat;

@Mixin(LevelRenderer.class)
public class MixinEntityCullingLevelRenderer {
	@Inject(method = "renderEntity", at = @At("HEAD"), require = 0)
	private void ip_onRenderEntity(
		Entity entity, double camX, double camY, double camZ, float partialTick,
		PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci
	) {
		EntityCullingCompat.onEntityRendered(entity);
	}
}
