package foundry.veil.impl.network;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Holds options set by the server for the client to follow.
 */
@ApiStatus.Internal
public final class VeilClientServerFlags {

    private static final Object2IntMap<ResourceLocation> ACTIVE_PIPELINES = new Object2IntOpenHashMap<>();

    static {
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, levelRenderer, bufferSource, matrixStack, frustumMatrix, projectionMatrix, renderTick, deltaTracker, camera, frustum) -> {
            if (stage == VeilRenderLevelStageEvent.Stage.AFTER_SKY) {
                if (ACTIVE_PIPELINES.isEmpty()) {
                    return;
                }

                PostProcessingManager postProcessingManager = VeilRenderSystem.renderer().getPostProcessingManager();
                for (Object2IntMap.Entry<ResourceLocation> entry : ACTIVE_PIPELINES.object2IntEntrySet()) {
                    postProcessingManager.add(entry.getIntValue(), entry.getKey());
                }
            }
        });
    }

    private VeilClientServerFlags() {
    }

    public static void addPipeline(int priority, ResourceLocation pipeline) {
        VeilRenderSystem.renderer().getPostProcessingManager().add(priority, pipeline);
        ACTIVE_PIPELINES.put(pipeline, priority);
    }

    public static void removePipeline(ResourceLocation pipeline) {
        VeilRenderSystem.renderer().getPostProcessingManager().remove(pipeline);
        ACTIVE_PIPELINES.removeInt(pipeline);
    }

    public static void clearPipelines() {
        PostProcessingManager postProcessingManager = VeilRenderSystem.renderer().getPostProcessingManager();
        for (ResourceLocation pipeline : ACTIVE_PIPELINES.keySet()) {
            postProcessingManager.remove(pipeline);
        }
        ACTIVE_PIPELINES.clear();
    }

    public static void onDisconnect() {
        clearPipelines();
    }
}
