package dev.simulated_team.simulated.content.blocks.void_anchor;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.content.end_sea.EndSeaShadowRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

public class VoidAnchorRenderer implements BlockEntityRenderer<VoidAnchorBlockEntity> {
    public VoidAnchorRenderer(final BlockEntityRendererProvider.Context context) {

    }

    @Override
    public boolean shouldRender(final VoidAnchorBlockEntity blockEntity, final Vec3 vec3) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 512;
    }

    @Override
    public boolean shouldRenderOffScreen(final VoidAnchorBlockEntity blockEntity) {
        return true;
    }

    @Override
    public void render(final VoidAnchorBlockEntity blockEntity, final float f, final PoseStack poseStack, final MultiBufferSource multiBufferSource, final int i, final int j) {
        if (EndSeaShadowRenderer.renderingShadowMap())
            return;

        EndSeaShadowRenderer.addVoidAnchor(blockEntity);
    }
}
