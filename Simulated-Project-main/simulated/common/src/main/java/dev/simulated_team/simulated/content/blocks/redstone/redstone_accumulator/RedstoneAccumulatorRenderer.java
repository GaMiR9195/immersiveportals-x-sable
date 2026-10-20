package dev.simulated_team.simulated.content.blocks.redstone.redstone_accumulator;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.index.SimPartialModels;
import foundry.veil.api.client.render.VeilRenderBridge;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class RedstoneAccumulatorRenderer extends SmartBlockEntityRenderer<RedstoneAccumulatorBlockEntity> {
    public static ResourceLocation SHADER_NAME = Simulated.path("redstone_accumulator/diode");
    public static RenderType DIODE_RENDER_TYPE = RenderType.create("redstone_accumulator_diode", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 131072, true, false,
            RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setShaderState(VeilRenderBridge.shaderState(SHADER_NAME))
                    .createCompositeState(true));

    public RedstoneAccumulatorRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final RedstoneAccumulatorBlockEntity be, final float partialTicks, final PoseStack ms, final MultiBufferSource buffer, final int light, final int overlay) {
        final SuperByteBuffer render = CachedBuffers.partial(SimPartialModels.REDSTONE_ACCUMULATOR_DIODE, be.getBlockState())
                .color(255, 255, 255, this.getLitAmount(be, partialTicks));

        final Direction facing = be.getBlockState().getValue(RedstoneAccumulatorBlock.FACING);
        render.light(light);
        render.translate(0.5, 0, 0.5);
        render.rotateYDegrees(AngleHelper.horizontalAngle(facing)).pushPose();
        render.renderInto(ms, buffer.getBuffer(DIODE_RENDER_TYPE));
    }

    private int getLitAmount(final RedstoneAccumulatorBlockEntity be, final float partialTicks) {
        float state = be.lerpedState.getValue(partialTicks);
        // ^1.5 is for gamma correction, otherwise dark change is too quick and light change is barely noticeable
        state = 1 - (float) Math.pow(state / 15F, 1.5);
        return (int)Mth.clamp(state * 255, 0, 255);
    }
}
