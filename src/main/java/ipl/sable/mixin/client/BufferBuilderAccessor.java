package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vertex count of a builder (per-block section ranges, see {@code IplSectionRanges}). */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {

    @Accessor("vertices")
    int ipl$getVertices();
}
