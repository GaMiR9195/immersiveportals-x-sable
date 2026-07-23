package foundry.veil.fabric.ext;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;

import java.util.ArrayDeque;
import java.util.Map;

public interface SodiumWorldRendererExtension {

    SortedRenderLists veil$getSortedRenderLists();

    Map<TaskQueueType, ArrayDeque<RenderSection>> veil$getTaskLists();

    void veil$setSortedRenderLists(SortedRenderLists sortedRenderLists);

    void veil$setTaskLists(Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists);
}
