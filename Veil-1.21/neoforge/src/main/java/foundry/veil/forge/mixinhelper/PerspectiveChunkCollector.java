package foundry.veil.forge.mixinhelper;

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import foundry.veil.forge.ext.RenderRegionExtension;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;

import java.util.Queue;

public class PerspectiveChunkCollector extends SectionCollector {

    private final TaskQueueType importantRebuildQueueType;
    private final TaskQueueType importantSortQueueType;
    private final ObjectArrayList<ChunkRenderList> renderLists;
    private boolean needsRevisitForPendingUpdates;

    public PerspectiveChunkCollector(TaskQueueType importantRebuildQueueType, TaskQueueType importantSortQueueType) {
        super(0, importantRebuildQueueType, importantSortQueueType);
        this.importantRebuildQueueType = importantRebuildQueueType;
        this.importantSortQueueType = importantSortQueueType;
        this.renderLists = new ObjectArrayList<>();
        this.needsRevisitForPendingUpdates = false;
    }

    @Override
    public void visit(RenderSection section) {
        int flags = section.getFlags();
        if (flags != 0) {
            RenderRegion region = section.getRegion();
            RenderRegionExtension ext = (RenderRegionExtension) region;
            ChunkRenderList renderList = ext.veil$getPerspectiveRenderList();
            if (renderList.getLastVisibleFrame() != VeilLevelPerspectiveRenderer.getID()) {
                renderList.reset(VeilLevelPerspectiveRenderer.getID(), this.orderIsSorted());
                this.renderLists.add(renderList);
            }

            renderList.add(section.getSectionIndex(), flags);
        }

        int pendingUpdate = section.getPendingUpdate();
        if (pendingUpdate != 0) {
            if (section.getRunningJob() != null) {
                this.needsRevisitForPendingUpdates = true;
                return;
            }

            TaskQueueType queueType = ChunkUpdateTypes.getQueueType(pendingUpdate, this.importantRebuildQueueType, this.importantSortQueueType);
            Queue<RenderSection> queue = this.getTaskLists().get(queueType);
            if (queue.size() < queueType.queueSizeLimit()) {
                queue.add(section);
            }
        }
    }

    @Override
    public ObjectArrayList<ChunkRenderList> getUnsortedRenderLists() {
        return this.renderLists;
    }

    @Override
    public boolean needsRevisitForPendingUpdates() {
        return this.needsRevisitForPendingUpdates;
    }

    @Override
    public boolean orderIsSorted() {
        return false;
    }
}
