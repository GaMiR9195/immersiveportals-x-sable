package dev.simulated_team.simulated.content.blocks.redstone.redstone_inductor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.ColoredOverlayBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.simulated_team.simulated.index.SimPartialModels;
import dev.simulated_team.simulated.util.SimColors;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class RedstoneInductorRenderer extends ColoredOverlayBlockEntityRenderer<RedstoneInductorBlockEntity> {

    public RedstoneInductorRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final RedstoneInductorBlockEntity be, final float partialTicks, final PoseStack ms, final MultiBufferSource buffer, final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        final SuperByteBuffer render = render(this.getOverlayBuffer(be), this.getColor(be, partialTicks), light);
        final Direction facing = be.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        render.translate(0.5, 0, 0.5);
        render.rotateYDegrees(AngleHelper.horizontalAngle(facing)).pushPose();
        render.renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }

    @Override
    protected int getColor(final RedstoneInductorBlockEntity te, final float partialTicks) {
        final float state = te.lerpedState.getValue(partialTicks);
        return SimColors.redstone(state / 15F);
    }

    @Override
    protected SuperByteBuffer getOverlayBuffer(final RedstoneInductorBlockEntity te) {
        return CachedBuffers.partial(SimPartialModels.REDSTONE_INDUCTOR_INDICATOR, te.getBlockState());
    }
}
