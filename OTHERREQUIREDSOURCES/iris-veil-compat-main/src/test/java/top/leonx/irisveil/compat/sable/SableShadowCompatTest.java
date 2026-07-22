package top.leonx.irisveil.compat.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableShadowCompatTest {
    @Test
    void fallsBackToPublicSableApiWhenSableInvokerMixinIsUnavailable() {
        SubLevelRenderDispatcher.TestHooks.reset();
        VanillaSubLevelBlockEntityRenderer.poseDuringRender = null;
        Matrix4f shadowModelView = new Matrix4f().translation(10.0f, 20.0f, 30.0f);

        boolean rendered = SableShadowCompat.renderSubLevelBlockEntities(
            new ClientLevel(),
            new RenderBuffers(),
            new BlockEntityRenderDispatcher(),
            shadowModelView,
            1.0,
            2.0,
            3.0,
            0.5f);

        assertTrue(rendered);
        assertTrue(SubLevelRenderDispatcher.TestHooks.renderBlockEntitiesCalled);
        assertMatrixEquals(
            new Matrix4f(shadowModelView).mul(SubLevelRenderDispatcher.ORIGINAL_POSE),
            VanillaSubLevelBlockEntityRenderer.poseDuringRender);
    }

    @Test
    void shadowBridgeHotPathDoesNotUseReflection() throws IOException {
        String compatSource = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/SableShadowCompat.java"));
        String bridgeSource = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/SableShadowBridge.java"));

        assertFalse(compatSource.contains("java.lang.reflect"), compatSource);
        assertFalse(compatSource.contains("Class.forName"), compatSource);
        assertFalse(compatSource.contains("Proxy"), compatSource);
        assertFalse(bridgeSource.contains("java.lang.reflect"), bridgeSource);
        assertFalse(bridgeSource.contains("Class.forName"), bridgeSource);
        assertFalse(bridgeSource.contains("Proxy"), bridgeSource);
    }

    @Test
    void sableBridgeUsesMixinInvokers() throws IOException {
        String bridgeSource = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/SableShadowBridge.java"));
        String dispatcherInvoker = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/mixin/MixinSubLevelRenderDispatcher.java"));
        String containerInvoker = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/mixin/MixinSubLevelContainer.java"));
        String rendererInvoker = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/mixin/MixinVanillaSubLevelBlockEntityRenderer.java"));

        assertTrue(bridgeSource.contains("MixinSubLevelContainer.irisveil$getClientContainer"), bridgeSource);
        assertTrue(bridgeSource.contains("MixinSubLevelRenderDispatcher.irisveil$get"), bridgeSource);
        assertTrue(bridgeSource.contains("irisveil$renderBlockEntities"), bridgeSource);
        assertTrue(bridgeSource.contains("MixinVanillaSubLevelBlockEntityRenderer.irisveil$create"), bridgeSource);
        assertTrue(dispatcherInvoker.contains("@Invoker"), dispatcherInvoker);
        assertTrue(containerInvoker.contains("@Invoker"), containerInvoker);
        assertTrue(rendererInvoker.contains("@Invoker"), rendererInvoker);
    }

    @Test
    void sableMixinPluginDoesNotLoadTargetClassesDuringPrepare() throws IOException {
        String pluginSource = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/sable/mixin/SableMixinPlugin.java"));

        assertFalse(pluginSource.contains("Class.forName"), pluginSource);
    }

    @Test
    void appliesShadowModelViewWhileSableRendersBlockEntities() {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(SubLevelRenderDispatcher.ORIGINAL_POSE);
        Matrix4f shadowModelView = new Matrix4f().translation(10.0f, 20.0f, 30.0f);
        CapturingBlockEntityRenderer delegate = new CapturingBlockEntityRenderer();
        ShadowModelViewBlockEntityRenderer renderer = new ShadowModelViewBlockEntityRenderer(
            delegate,
            new BlockEntityRenderDispatcher(),
            shadowModelView);

        renderer.renderBlockEntities(List.of(new BlockEntity()), poseStack, 0.5f, 1.0, 2.0, 3.0);

        assertMatrixEquals(
            new Matrix4f(shadowModelView).mul(SubLevelRenderDispatcher.ORIGINAL_POSE),
            delegate.poseDuringRender);
        assertMatrixEquals(
            SubLevelRenderDispatcher.ORIGINAL_POSE,
            poseStack.last().pose());
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(row, column), actual.get(row, column), 0.0001f);
            }
        }
    }

    private static final class CapturingBlockEntityRenderer implements SubLevelRenderDispatcher.BlockEntityRenderer {
        private Matrix4f poseDuringRender;

        @Override
        public void renderBlockEntities(
                Collection<BlockEntity> blockEntities,
                PoseStack poseStack,
                float partialTick,
                double cameraX,
                double cameraY,
                double cameraZ) {
            poseDuringRender = new Matrix4f(poseStack.last().pose());
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
            return new BlockEntityRenderDispatcher();
        }
    }
}
