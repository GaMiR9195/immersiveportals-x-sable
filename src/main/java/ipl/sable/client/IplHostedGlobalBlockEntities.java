package ipl.sable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.mixinterface.BlockEntityRenderDispatcherExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;

/**
 * Vanilla excludes off-screen BERs from a section's renderable list and puts
 * them in LevelRenderer.globalBlockEntities instead. Hosted sections report to
 * the STORAGE renderer, which is never drawn in an ordinary parent-world pass.
 * Render that missing category in the SAME ship/clip/buffer scope as normal BEs.
 * This covers ropes and other global BERs without a Simulated-specific renderer.
 */
public final class IplHostedGlobalBlockEntities {
    private IplHostedGlobalBlockEntities() {}

    public static void render(
        ClientSubLevel sub, VanillaSubLevelBlockEntityRenderer renderer,
        Vec3 camera, float partialTick
    ) {
        if (!(sub.getRenderData() instanceof VanillaChunkedSubLevelRenderData data)) return;
        BlockEntityRenderDispatcher dispatcher = renderer.getBlockEntityRenderDispatcher();
        var globals = new ArrayList<BlockEntity>();
        for (var holder : sub.getPlot().getLoadedChunks()) {
            var chunk = holder.getChunk();
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!be.isRemoved() && isGlobal(dispatcher, be)) globals.add(be);
            }
        }
        if (globals.isEmpty()) return;

        Matrix4f transform = data.getTransformation(camera.x, camera.y, camera.z, new Matrix4f());
        var rotationPoint = sub.renderPose().rotationPoint();
        Vector3f localCamera = new Matrix4f(transform).invert().transformPosition(new Vector3f());
        var extension = (BlockEntityRenderDispatcherExtension) dispatcher;
        extension.sable$setCameraPosition(new Vec3(
            localCamera.x + rotationPoint.x(), localCamera.y + rotationPoint.y(),
            localCamera.z + rotationPoint.z()));
        PoseStack matrices = new PoseStack();
        matrices.mulPose(transform);
        try {
            renderer.renderBlockEntities(globals, matrices, partialTick,
                rotationPoint.x(), rotationPoint.y(), rotationPoint.z());
        } finally {
            extension.sable$setCameraPosition(null);
        }
    }

    private static <T extends BlockEntity> boolean isGlobal(
        BlockEntityRenderDispatcher dispatcher, T be
    ) {
        BlockEntityRenderer<T> renderer = dispatcher.getRenderer(be);
        return renderer != null && renderer.shouldRenderOffScreen(be);
    }
}