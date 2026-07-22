package dev.eriksonn.aeronautics.content.blocks.propeller.small;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public abstract class SimplePropellerRenderer<T extends BasePropellerBlockEntity> extends KineticBlockEntityRenderer<T> {

    public SimplePropellerRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void renderSafe(final T be, final float partialTicks, final PoseStack ms, final MultiBufferSource buffer, final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

        final BlockState state = be.getBlockState();
        final Direction dir = state.getValue(BlockStateProperties.FACING);

        final VertexConsumer vb = buffer.getBuffer(RenderType.solid());

        final SuperByteBuffer propeller = CachedBuffers.partialFacing(this.getCurrentModel(be), state);

        final float angle = this.getAngle(partialTicks, dir, be);
        kineticRotationTransform(propeller, be, dir.getAxis(), angle, light);

        if (dir.getAxis().isHorizontal()) {
            propeller.rotateCentered(AngleHelper.rad(AngleHelper.horizontalAngle(dir.getOpposite())), Direction.UP);
        }
        if (dir.getAxis().isVertical()) {
            propeller.rotateCentered(AngleHelper.rad(AngleHelper.verticalAngle(dir.getOpposite())), Direction.EAST);
        }

        propeller.translate(0, 0, -3 / 16f).rotateCentered(AngleHelper.rad(-90 - AngleHelper.verticalAngle(dir)), Direction.EAST);

        propeller.renderInto(ms, vb);
    }

    public abstract PartialModel getCurrentModel(T be);

    public float getAngle(final float partialTicks, final Direction dir, final T be) {
        float angle = be.getPreviousAngle() * (1f - partialTicks) + be.getAngle() * partialTicks;

        angle = angle / 180f * (float) Math.PI;

        angle *= 2;

        return angle;
    }

    @Override
    protected SuperByteBuffer getRotatedModel(final T be, final BlockState state) {
        return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, state
                .getValue(BearingBlock.FACING)
                .getOpposite());
    }
}