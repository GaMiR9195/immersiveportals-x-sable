package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

/** Includes global/off-screen BERs, which compiled section lists deliberately omit. */
public final class IplSubLevelBlockEntityPresence {
    private IplSubLevelBlockEntityPresence() {}
    public static boolean hasBlockEntities(ClientSubLevel sub) {
        var data=sub.getRenderData();
        if (data instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData chunks) {
            for (var section:chunks.allRenderSections())
                if (!section.getCompiled().getRenderableBlockEntities().isEmpty()) return true;
            // Compiled sections EXCLUDE shouldRenderOffScreen BERs. They live
            // in the plot chunks and are submitted by IplHostedGlobalBlockEntities.
            // An empty compiled list must not suppress the ENTIRE enclosing pass.
            for (var holder:sub.getPlot().getLoadedChunks()) {
                var chunk=holder.getChunk();
                if(chunk!=null) for(var be:chunk.getBlockEntities().values())
                    if(!be.isRemoved())return true;
            }
            return false;
        }
        if (data instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData single)
            return single.getRenderBlockEntity()!=null;
        return true; // A custom backend can have its own BE storage.
    }

}
