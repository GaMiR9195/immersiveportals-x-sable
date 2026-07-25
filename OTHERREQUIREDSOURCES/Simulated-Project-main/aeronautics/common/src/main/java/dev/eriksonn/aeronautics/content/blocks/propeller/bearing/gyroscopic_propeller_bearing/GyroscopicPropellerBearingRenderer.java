package dev.eriksonn.aeronautics.content.blocks.propeller.bearing.gyroscopic_propeller_bearing;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.eriksonn.aeronautics.index.AeroPartialModels;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public class GyroscopicPropellerBearingRenderer extends KineticBlockEntityRenderer<GyroscopicPropellerBearingBlockEntity> {

    public GyroscopicPropellerBearingRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final GyroscopicPropellerBearingBlockEntity be, final float partialTicks, final PoseStack ms, final MultiBufferSource buffer, final int light, final int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) return;

        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

        final Direction facing = be.getBlockState().getValue(BlockStateProperties.FACING);
        final Vec3 normal = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        final Quaternionf tiltQuat = new Quaternionf(be.previousTiltQuat).slerp(be.tiltQuat, partialTicks);
        final Quaternionf Q = new Quaternionf(tiltQuat);
        Q.conjugate();
        Q.mul(new Quaternionf((float) normal.x, (float) normal.y, (float) normal.z, 0f));
        Q.mul(tiltQuat);
        final Vec3 contraptionNormal = new Vec3(Q.x(), Q.y(), Q.z());

        final PartialModel top = AeroPartialModels.BEARING_PLATE_METAL;
        final SuperByteBuffer superBuffer = CachedBuffers.partial(top, be.getBlockState());

        superBuffer.translate(normal.scale(4 / 16f));
        superBuffer.rotateCentered(tiltQuat);
        superBuffer.translate(normal.scale(-4 / 16f));

        final float interpolatedAngle = be.getInterpolatedAngle(partialTicks - 1);
        kineticRotationTransform(superBuffer, be, facing.getAxis(), (float) (interpolatedAngle / 180 * Math.PI), light);

        if (facing.getAxis()
                .isHorizontal()) {
            superBuffer.rotateCentered(
                    AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite())), Direction.UP);
        }

        superBuffer.rotateCentered(AngleHelper.rad(-90 - AngleHelper.verticalAngle(facing)), Direction.EAST);
        superBuffer.renderInto(ms, buffer.getBuffer(RenderType.solid()));


        for (int i = 0; i < 4; i++) {

            final SuperByteBuffer headBuffer = CachedBuffers.partial(AeroPartialModels.GYRO_BEARING_PISTON_HEAD, be.getBlockState());
            final SuperByteBuffer poleBuffer = CachedBuffers.partial(AeroPartialModels.GYRO_BEARING_PISTON_POLE, be.getBlockState());
            final Vec3 originalPos = VecHelper.rotate(new Vec3(5.9 / 16.0, 0, 0), -90 * i, Direction.Axis.Y);
            Vec3 translatedPos = originalPos;

            if (facing.getAxis().isHorizontal()) {

                translatedPos = VecHelper.rotate(translatedPos, AngleHelper.horizontalAngle(facing), Direction.Axis.Z);
                translatedPos = VecHelper.rotate(translatedPos, -90 + AngleHelper.verticalAngle(facing), Direction.Axis.X);
            }

            final double translateDistance = translatedPos.dot(contraptionNormal) / normal.dot(contraptionNormal);
            translatedPos = translatedPos.add(normal.scale(translateDistance + 3 / 16.0));

            headBuffer.translate(translatedPos);
            headBuffer.translate(0.5f, 0.5f, 0.5f);

            poleBuffer.translate(translatedPos);
            poleBuffer.translate(0.5f, 0.5f, 0.5f);

            headBuffer.rotate(tiltQuat);
            int j = i;
            if (facing == Direction.DOWN) {
                if (i % 2 == 0) {
                    headBuffer.rotate(AngleHelper.rad(180), Direction.EAST);
                    poleBuffer.rotate(AngleHelper.rad(180), Direction.EAST);
                } else {
                    headBuffer.rotate(AngleHelper.rad(180), Direction.SOUTH);
                    poleBuffer.rotate(AngleHelper.rad(180), Direction.SOUTH);
                }
            }
            if (facing.getAxis().isHorizontal()) {

                headBuffer.rotate(AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite())), Direction.UP);
                poleBuffer.rotate(AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite())), Direction.UP);
                headBuffer.rotate(AngleHelper.rad(-90 + AngleHelper.verticalAngle(facing)), Direction.EAST);
                poleBuffer.rotate(AngleHelper.rad(-90 + AngleHelper.verticalAngle(facing)), Direction.EAST);
                j = 2 - j;
            }

            poleBuffer.translate(0, 0.5f / 16.0, 0);

            headBuffer.rotate(AngleHelper.rad(-90 * j), Direction.UP);
            poleBuffer.rotate(AngleHelper.rad(-90 * j), Direction.UP);

            headBuffer.light(light).renderInto(ms, buffer.getBuffer(RenderType.solid()));
            poleBuffer.light(light).renderInto(ms, buffer.getBuffer(RenderType.solid()));

        }
    }

    @Override
    protected SuperByteBuffer getRotatedModel(final GyroscopicPropellerBearingBlockEntity be, final BlockState state) {
        return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, state
                .getValue(BearingBlock.FACING)
                .getOpposite());
    }
}

