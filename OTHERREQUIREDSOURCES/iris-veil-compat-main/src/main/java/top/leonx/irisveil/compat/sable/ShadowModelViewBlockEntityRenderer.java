package top.leonx.irisveil.compat.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Collection;

final class ShadowModelViewBlockEntityRenderer implements SubLevelRenderDispatcher.BlockEntityRenderer {
    private final SubLevelRenderDispatcher.BlockEntityRenderer delegate;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final Matrix4f shadowModelView;

    ShadowModelViewBlockEntityRenderer(
            SubLevelRenderDispatcher.BlockEntityRenderer delegate,
            BlockEntityRenderDispatcher blockEntityRenderDispatcher,
            Matrix4f shadowModelView) {
        this.delegate = delegate;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.shadowModelView = new Matrix4f(shadowModelView);
    }

    @Override
    public void renderBlockEntities(
            Collection<BlockEntity> blockEntities,
            PoseStack poseStack,
            float partialTick,
            double cameraX,
            double cameraY,
            double cameraZ) {
        withShadowModelView(poseStack, () -> delegate.renderBlockEntities(
            blockEntities,
            poseStack,
            partialTick,
            cameraX,
            cameraY,
            cameraZ));
    }

    @Override
    public void renderSingleBE(
            BlockEntity blockEntity,
            PoseStack poseStack,
            float partialTick,
            double cameraX,
            double cameraY,
            double cameraZ) {
        withShadowModelView(poseStack, () -> delegate.renderSingleBE(
            blockEntity,
            poseStack,
            partialTick,
            cameraX,
            cameraY,
            cameraZ));
    }

    @Override
    public BlockEntityRenderDispatcher getBlockEntityRenderDispatcher() {
        return blockEntityRenderDispatcher;
    }

    private void withShadowModelView(PoseStack poseStack, Runnable renderer) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f poseMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        Matrix4f originalPose = new Matrix4f(poseMatrix);
        Matrix3f originalNormal = new Matrix3f(normalMatrix);

        poseMatrix.set(new Matrix4f(shadowModelView).mul(originalPose));
        normalMatrix.set(poseMatrix).invert().transpose();
        try {
            renderer.run();
        } finally {
            poseMatrix.set(originalPose);
            normalMatrix.set(originalNormal);
        }
    }
}
