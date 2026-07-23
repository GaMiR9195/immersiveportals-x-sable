package top.leonx.irisveil.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.level.BlockDestructionProgress;
import org.joml.Matrix4f;
import top.leonx.irisveil.compat.sable.mixin.MixinSubLevelContainer;
import top.leonx.irisveil.compat.sable.mixin.MixinSubLevelRenderDispatcher;
import top.leonx.irisveil.compat.sable.mixin.MixinVanillaSubLevelBlockEntityRenderer;

import java.util.List;
import java.util.SortedSet;

final class SableShadowBridge {
    private SableShadowBridge() {
    }

    static boolean renderSubLevelBlockEntities(
            Object level,
            Object renderBuffers,
            Object blockEntityRenderDispatcher,
            Object shadowModelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        if (!(level instanceof ClientLevel clientLevel)
                || !(renderBuffers instanceof RenderBuffers shadowRenderBuffers)
                || !(blockEntityRenderDispatcher instanceof BlockEntityRenderDispatcher dispatcher)
                || !(shadowModelView instanceof Matrix4f shadowModelViewMatrix)) {
            return false;
        }

        ClientSubLevelContainer container = getClientContainer(clientLevel);
        if (container == null) {
            return false;
        }

        List<ClientSubLevel> subLevels = container.getAllSubLevels();
        if (subLevels.isEmpty()) {
            return false;
        }

        SubLevelRenderDispatcher subLevelDispatcher = getSubLevelDispatcher();
        VanillaSubLevelBlockEntityRenderer vanillaRenderer = createVanillaRenderer(dispatcher, shadowRenderBuffers);

        SubLevelRenderDispatcher.BlockEntityRenderer shadowRenderer =
            new ShadowModelViewBlockEntityRenderer(vanillaRenderer, dispatcher, shadowModelViewMatrix);
        renderBlockEntities(
            subLevelDispatcher,
            subLevels,
            shadowRenderer,
            cameraX,
            cameraY,
            cameraZ,
            partialTick);
        return true;
    }

    private static ClientSubLevelContainer getClientContainer(ClientLevel clientLevel) {
        try {
            return MixinSubLevelContainer.irisveil$getClientContainer(clientLevel);
        } catch (AssertionError | LinkageError e) {
            return SubLevelContainer.getContainer(clientLevel);
        }
    }

    private static SubLevelRenderDispatcher getSubLevelDispatcher() {
        try {
            return MixinSubLevelRenderDispatcher.irisveil$get();
        } catch (AssertionError | LinkageError e) {
            return SubLevelRenderDispatcher.get();
        }
    }

    private static VanillaSubLevelBlockEntityRenderer createVanillaRenderer(
            BlockEntityRenderDispatcher dispatcher,
            RenderBuffers shadowRenderBuffers) {
        Long2ObjectOpenHashMap<SortedSet<BlockDestructionProgress>> destructionProgress =
            new Long2ObjectOpenHashMap<>();

        try {
            return MixinVanillaSubLevelBlockEntityRenderer.irisveil$create(
                dispatcher,
                shadowRenderBuffers,
                destructionProgress);
        } catch (AssertionError | LinkageError e) {
            return new VanillaSubLevelBlockEntityRenderer(dispatcher, shadowRenderBuffers, destructionProgress);
        }
    }

    private static void renderBlockEntities(
            SubLevelRenderDispatcher subLevelDispatcher,
            List<ClientSubLevel> subLevels,
            SubLevelRenderDispatcher.BlockEntityRenderer shadowRenderer,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        try {
            ((MixinSubLevelRenderDispatcher) subLevelDispatcher).irisveil$renderBlockEntities(
                subLevels,
                shadowRenderer,
                cameraX,
                cameraY,
                cameraZ,
                partialTick);
        } catch (AssertionError | ClassCastException | LinkageError e) {
            subLevelDispatcher.renderBlockEntities(
                subLevels,
                shadowRenderer,
                cameraX,
                cameraY,
                cameraZ,
                partialTick);
        }
    }
}
