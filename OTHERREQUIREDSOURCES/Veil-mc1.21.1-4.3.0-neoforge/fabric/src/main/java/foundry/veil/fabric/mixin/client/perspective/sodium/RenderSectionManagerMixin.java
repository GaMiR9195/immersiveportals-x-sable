package foundry.veil.fabric.mixin.client.perspective.sodium;

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import foundry.veil.fabric.ext.RenderSectionExtension;
import foundry.veil.fabric.mixinhelper.PerspectiveChunkCollector;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Map;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {

    @Shadow
    private @NotNull Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;

    @Shadow
    @Final
    private OcclusionCuller occlusionCuller;

    @Shadow
    protected abstract boolean shouldUseOcclusionCulling(Camera camera, boolean spectator);

    @Shadow
    protected abstract float getSearchDistance();

    @Shadow
    protected abstract void resetRenderLists();

    @Shadow
    protected abstract RenderSection getRenderSection(int x, int y, int z);

    @Shadow
    @Final
    private SortBehavior sortBehavior;

    @Shadow
    private SectionCollector sectionCollector;

    @Shadow
    private SectionCollector lastSectionCollector;

    @Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
    private void createTerrainRenderList(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfoReturnable<Boolean> cir) {
        if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            return;
        }

        this.resetRenderLists();
        float searchDistance = this.getSearchDistance();
        boolean useOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);
        TaskQueueType importantRebuildQueueType = SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType();
        TaskQueueType importantSortQueueType = this.sortBehavior.getDeferMode().getImportantRebuildQueueType();

        SectionCollector visitor = new PerspectiveChunkCollector(importantRebuildQueueType, importantSortQueueType);
        this.occlusionCuller.findVisible(visitor, viewport, searchDistance, useOcclusionCulling, frame);
        this.sectionCollector = visitor;

        this.lastSectionCollector = null;
        this.taskLists = this.sectionCollector.getTaskLists();
        cir.setReturnValue(this.sectionCollector.needsRevisitForPendingUpdates());
    }

    @Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
    public void isSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            return;
        }

        RenderSection render = this.getRenderSection(x, y, z);
        cir.setReturnValue(render != null && !((RenderSectionExtension) render).veil$hasNotRendered());
    }
}
