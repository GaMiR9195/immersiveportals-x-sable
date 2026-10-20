package foundry.veil.fabric.ext;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;

public interface RenderRegionExtension {

    ChunkRenderList veil$getPerspectiveRenderList();
}
