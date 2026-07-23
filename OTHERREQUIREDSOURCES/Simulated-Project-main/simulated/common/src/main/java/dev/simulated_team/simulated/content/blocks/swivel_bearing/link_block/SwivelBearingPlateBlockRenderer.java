package dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.index.SimPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

public class SwivelBearingPlateBlockRenderer extends KineticBlockEntityRenderer<SwivelBearingPlateBlockEntity> {

    public SwivelBearingPlateBlockRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(final SwivelBearingPlateBlockEntity be, final BlockState state) {
        return CachedBuffers.partialFacing(SimPartialModels.SHAFT_SIXTEENTH, state, state.getValue(SwivelBearingBlock.FACING));
    }
}
