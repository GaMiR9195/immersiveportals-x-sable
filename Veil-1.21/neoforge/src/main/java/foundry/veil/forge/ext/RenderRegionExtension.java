package foundry.veil.forge.ext;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;

public interface RenderRegionExtension {

    ChunkRenderList veil$getPerspectiveRenderList();
}
