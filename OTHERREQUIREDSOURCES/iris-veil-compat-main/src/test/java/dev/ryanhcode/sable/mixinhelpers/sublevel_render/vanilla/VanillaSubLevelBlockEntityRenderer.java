package dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;

import java.util.SortedSet;

public final class VanillaSubLevelBlockEntityRenderer implements SubLevelRenderDispatcher.BlockEntityRenderer {
    public static Object lastRenderBuffers;
    public static Object lastBlockEntityRenderDispatcher;
    public static Matrix4f poseDuringRender;

    public VanillaSubLevelBlockEntityRenderer(
            BlockEntityRenderDispatcher blockEntityRenderDispatcher,
            RenderBuffers renderBuffers,
            Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        lastBlockEntityRenderDispatcher = blockEntityRenderDispatcher;
        lastRenderBuffers = renderBuffers;
    }

    @Override
    public void renderSingleBE(
            BlockEntity blockEntity,
            PoseStack poseStack,
            float partialTick,
            double cameraX,
            double cameraY,
            double cameraZ) {
        poseDuringRender = new Matrix4f(poseStack.last().pose());
    }

    @Override
    public BlockEntityRenderDispatcher getBlockEntityRenderDispatcher() {
        return (BlockEntityRenderDispatcher) lastBlockEntityRenderDispatcher;
    }
}
