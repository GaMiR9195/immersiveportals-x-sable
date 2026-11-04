package ipl.sable.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicReference;

/** The graph state of a renderer; it is null until the renderer has a view area. */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {

    @Accessor("currentGraph")
    AtomicReference<?> ipl$getCurrentGraph();
}
