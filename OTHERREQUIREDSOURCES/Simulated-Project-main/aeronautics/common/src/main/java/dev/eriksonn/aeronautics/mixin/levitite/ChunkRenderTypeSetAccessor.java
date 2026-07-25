package dev.eriksonn.aeronautics.mixin.levitite;

import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;
import java.util.List;

@Mixin(ChunkRenderTypeSet.class)
public interface ChunkRenderTypeSetAccessor {

    @Mutable
    @Accessor("CHUNK_RENDER_TYPES_LIST")
    static void setChunkRenderTypesList(final List<RenderType> data) { throw new AssertionError("Something has gone terribly wrong."); }// = RenderType.chunkBufferLayers();

    @Accessor("CHUNK_RENDER_TYPES")
    @Mutable
    static void setChunkRenderTypes(final RenderType[] data) { throw new AssertionError("Something has gone terribly wrong."); }// = CHUNK_RENDER_TYPES_LIST.toArray(new RenderType[0]);

    @Accessor("bits")
    BitSet getBits();

}
