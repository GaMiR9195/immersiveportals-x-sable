package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the section-compiled occlusion notification while the renderer has no occlusion graph.
 *
 * <p>IP's secondary-world renderers (client levels seen through portals) receive section
 * lifecycle traffic before their first render pass: on (re)join, a sub-level's finalize packet
 * triggers {@code ClientSubLevel.updateRenderData}, whose {@code close()} of the previous render
 * data finalizes sections. {@code SectionOcclusionGraph.onSectionCompiled} dereferences the graph
 * state, which exists only once the renderer has a view area: an NPE that disconnects the client.
 * Without a graph there is nothing to notify — the first render builds the full graph anyway.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOcclusionMixin {

    @WrapOperation(
        method = "addRecentlyCompiledSection",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;onSectionCompiled(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)V"
        )
    )
    private void ipl$skipWithoutGraph(
        SectionOcclusionGraph graph, SectionRenderDispatcher.RenderSection section,
        Operation<Void> original
    ) {
        if (((SectionOcclusionGraphAccessor) graph).ipl$getCurrentGraph().get() != null) {
            original.call(graph, section);
        }
    }
}
