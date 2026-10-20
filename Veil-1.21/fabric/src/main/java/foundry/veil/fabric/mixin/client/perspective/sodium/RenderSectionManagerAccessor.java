package foundry.veil.fabric.mixin.client.perspective.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;

@Mixin(RenderSectionManager.class)
public interface RenderSectionManagerAccessor {

    @Accessor(remap = false)
    Map<TaskQueueType, ArrayDeque<RenderSection>> getTaskLists();

    @Accessor(remap = false)
    void setRenderLists(SortedRenderLists renderLists);

    @Accessor(remap = false)
    void setTaskLists(Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists);
}
