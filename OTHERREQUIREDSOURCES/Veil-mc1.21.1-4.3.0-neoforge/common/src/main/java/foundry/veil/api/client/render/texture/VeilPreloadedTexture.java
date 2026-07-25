package foundry.veil.api.client.render.texture;

import net.minecraft.server.packs.resources.ResourceManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Allows a texture to hook into the preloading logic.
 */
public interface VeilPreloadedTexture {

    CompletableFuture<?> preload(ResourceManager resourceManager, Executor backgroundExecutor);
}
