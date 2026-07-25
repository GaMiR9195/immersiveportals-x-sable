package foundry.veil.forge.mixin.client.perspective.sodium;

import foundry.veil.forge.ext.SodiumWorldRendererExtension;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayDeque;
import java.util.Map;

@Mixin(SodiumWorldRenderer.class)
public class SodiumWorldRendererMixin implements SodiumWorldRendererExtension {

    @Shadow(remap = false)
    private RenderSectionManager renderSectionManager;

    @Override
    public SortedRenderLists veil$getSortedRenderLists() {
        return this.renderSectionManager.getRenderLists();
    }

    @Override
    public Map<TaskQueueType, ArrayDeque<RenderSection>> veil$getTaskLists() {
        return ((RenderSectionManagerAccessor) this.renderSectionManager).getTaskLists();
    }

    @Override
    public void veil$setSortedRenderLists(SortedRenderLists sortedRenderLists) {
        ((RenderSectionManagerAccessor) this.renderSectionManager).setRenderLists(sortedRenderLists);
    }

    @Override
    public void veil$setTaskLists(Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists) {
        ((RenderSectionManagerAccessor) this.renderSectionManager).setTaskLists(taskLists);
    }
}