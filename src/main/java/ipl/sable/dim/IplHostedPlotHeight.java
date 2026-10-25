package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;

/** Position-specific profile; never changes a Level's own chunk-section layout. */
public final class IplHostedPlotHeight {
    private IplHostedPlotHeight() {}

    public static LevelHeightAccessor profile(Level level, BlockPos pos) {
        if (IplDimAgnostic.isHostingLevel(level)) return level;
        SubLevelContainer storage = IplDimAgnostic.getHostingContainerFor(level);
        if (storage == null) return level;
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        if (!storage.inBounds(x, z)) return level;
        var plot = storage.getPlot(x, z);
        var sub = plot == null ? null : plot.getSubLevel();
        return sub != null && !sub.isRemoved() && IplDimAgnostic.getParentLevel(sub) == level
            ? sub.getLevel() : level;
    }
}
