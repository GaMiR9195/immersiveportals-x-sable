package foundry.veil.fabric.mixin.client.perspective.sodium;

import foundry.veil.fabric.ext.RenderRegionExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderRegion.class)
public class RenderRegionMixin implements RenderRegionExtension {

    @Unique
    private ChunkRenderList veil$perspectiveList;

    @Override
    public ChunkRenderList veil$getPerspectiveRenderList() {
        if (this.veil$perspectiveList == null) {
            this.veil$perspectiveList = new ChunkRenderList((RenderRegion) (Object) this);
        }
        return this.veil$perspectiveList;
    }
}
