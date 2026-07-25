package dev.simulated_team.simulated.mixin.accessor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Invoker
    static void invokeRenderShape(final PoseStack poseStack, final VertexConsumer consumer, final VoxelShape shape, final double x, final double y, final double z, final float red, final float green, final float blue, final float alpha) {}
}
