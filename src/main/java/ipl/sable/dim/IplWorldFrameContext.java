package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-local "which parent dimension is this hosted code REALLY acting in" context.
 *
 * <p>Sable's Create-compat layer (drills, deployers, saws, harvesters) maps positions out of
 * a sub-level through {@code logicalPose()} into parent-frame world coordinates, then reads
 * and writes them through the block entity's own level. Pre-dim-agnostic that level WAS the
 * parent dimension, so plot reads and terrain reads hit the same world. Hosted, the BE's
 * level is {@code ipl_sable:sublevels} — empty at world coordinates — so every terrain
 * interaction silently sees air.
 *
 * <p>This context is set around each plot-chunk block entity tick (see
 * {@code IplHostedBeTickContextMixin}), resolving the ticking BE's plot → owner sub-level →
 * parent level. {@code IplHostedWorldFrameRouterMixin} then routes world-frame (non-plot)
 * block access on the hosting level to this parent. The pair is the mirror image of
 * {@code IplPlotDeferredLogicMixin}, which routes plot-frame access from parent levels INTO
 * the hosting level.
 */
public final class IplWorldFrameContext {

    private static final ThreadLocal<ServerLevel> CURRENT = new ThreadLocal<>();

    private IplWorldFrameContext() {}

    /** The parent level hosted BE code is conceptually acting in, or null outside a hosted BE tick. */
    @Nullable
    public static ServerLevel current() {
        return CURRENT.get();
    }

    /** Set the context, returning the previous value for restoration in a finally block. */
    @Nullable
    public static ServerLevel push(ServerLevel parent) {
        ServerLevel prev = CURRENT.get();
        CURRENT.set(parent);
        return prev;
    }

    public static void pop(@Nullable ServerLevel prev) {
        CURRENT.set(prev);
    }

    /**
     * For a block entity ticking at {@code pos} on {@code level}: if this is a plot-chunk BE
     * on the hosting dimension, the parent level of the owning sub-level. Null otherwise.
     */
    @Nullable
    public static ServerLevel resolveParentForPlotBe(Level level, BlockPos pos) {
        if (!IplDimAgnostic.isHostingLevel(level)) return null;
        if (!(level instanceof ServerLevel hosting)) return null;
        // Plot-grid coords are in the millions; nothing else on the hosting level ticks.
        if (Math.abs(pos.getX()) < 1_000_000 && Math.abs(pos.getZ()) < 1_000_000) return null;

        SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
        if (container == null) return null;
        LevelPlot plot = container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return null;
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null) return null;
        return IplDimAgnostic.getServerParentLevel(subLevel);
    }
}
