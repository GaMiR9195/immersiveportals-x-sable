package dev.simulated_team.simulated.content.blocks.gimbal_sensor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.simulated_team.simulated.index.SimPartialModels;
import dev.simulated_team.simulated.util.SimColors;
import dev.simulated_team.simulated.util.SimDirectionUtil;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

public class GimbalSensorRenderer extends SafeBlockEntityRenderer<GimbalSensorBlockEntity> {
    public GimbalSensorRenderer(final BlockEntityRendererProvider.Context context) {

    }

    @Override
    protected void renderSafe(final GimbalSensorBlockEntity be, final float partialTicks, final PoseStack ms, final MultiBufferSource buffer, final int light, final int overlay) {
        FilteringRenderer.renderOnBlockEntity(be, partialTicks, ms, buffer, light, overlay);

        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        final VertexConsumer vb = buffer.getBuffer(RenderType.cutout());
        final Quaternionf Q = be.getBaseQuaternion();

        // Render Redstone Indicators
        ms.pushPose();
        ms.translate(0.5, 0, 0.5);
        for (final Direction direction : SimDirectionUtil.Y_AXIS_PLANE) {
            ms.pushPose();
            final SuperByteBuffer indicator = CachedBuffers.partial(SimPartialModels.GIMBAL_SENSOR_INDICATOR, be.getBlockState());

            indicator.rotateToFace(direction);
            indicator.translate(0, 0, -0.5);
            final float signalStrength = Math.max(be.getPower(direction), 0) / 15.0F;
            final int color = SimColors.redstone(signalStrength); // Analog indicators (mixes between colors smoothly)
            // int color = (signalStrength > 0) ? 0xCD0000 : 0x630002; // Digital indicators (on/off only)
            indicator.light(light)
                    .color(color)
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            ms.popPose();
        }

        ms.popPose();
        be.applyPrimaryQuaternion(Q, partialTicks);
        this.apply(SimPartialModels.GIMBAL_SENSOR_GIMBAL, be, Q, light, ms, vb);
        be.applySecondaryQuaternion(Q, partialTicks);
        this.apply(SimPartialModels.GIMBAL_SENSOR_COMPASS, be, Q, light, ms, vb);
        be.applyCompassQuaternion(Q, partialTicks);
        this.apply(SimPartialModels.GIMBAL_SENSOR_NEEDLE, be, Q, light, ms, vb);
    }

    private void apply(final PartialModel model, final GimbalSensorBlockEntity te, final Quaternionf Q, final int light, final PoseStack ms, final VertexConsumer vb) {
        final SuperByteBuffer buf = CachedBuffers.partial(model, te.getBlockState());
        buf.rotateCentered(Q);
        buf.translate(0.5, 0.5, 0.5);
        buf.light(light).renderInto(ms, vb);
    }
}
