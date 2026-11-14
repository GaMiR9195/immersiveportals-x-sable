package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import ipl.sable.render.route.IplGeometryRouting;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The one funnel of every block-entity draw (the world's and every sub-level's): a model near
 * a straddling body's opening is written with the parts its route through the openings gives
 * to this draw (see {@link IplGeometryRouting}).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherRoutingMixin {

	@WrapMethod(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
	private void ipl$routeThroughApertures(BlockEntity be, float partialTick, PoseStack poseStack,
										   MultiBufferSource buffers, Operation<Void> original) {
		IplGeometryRouting.render(be, buffers, target -> original.call(be, partialTick, poseStack, target));
	}
}
