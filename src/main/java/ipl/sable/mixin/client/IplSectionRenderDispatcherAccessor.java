package ipl.sable.mixin.client;

import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives a sub-level section dispatcher its own buffer pool (see {@code IplPerLevelSubLevelRenderDispatcher}). */
@Mixin(SectionRenderDispatcher.class)
public interface IplSectionRenderDispatcherAccessor {

    @Mutable
    @Accessor("bufferPool")
    void ipl$setBufferPool(SectionBufferBuilderPool pool);
}
