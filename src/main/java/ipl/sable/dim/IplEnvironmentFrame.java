package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Environmental query boundary for legacy APIs. Storage and physics ownership
 * NEVER change. A position in a live plot names its owner without a heuristic;
 * world-space positions require an explicitly armed frame. Unresolved queries
 * stay in storage rather than guessing a player or the nearest ship.
 */
public final class IplEnvironmentFrame {
    private IplEnvironmentFrame() {}

    public record Sample(Level level, Vector3dc position) {}

    @Nullable
    public static Level contextualLevel(Level source) {
        if (!IplDimAgnostic.isHostingLevel(source)) return null;
        Level context = source.isClientSide
            ? ipl.sable.client.IplClientEnvironmentBridge.current() : IplWorldFrameContext.current();
        return context == source || IplDimAgnostic.isHostingLevel(context) ? null : context;
    }

    public static Sample resolve(Level source, Vector3dc position) {
        if (!IplDimAgnostic.isHostingLevel(source)) return new Sample(source, position);
        SubLevelContainer container = SubLevelContainer.getContainer(source);
        if (container != null) {
            int x = net.minecraft.util.Mth.floor(position.x()) >> 4;
            int z = net.minecraft.util.Mth.floor(position.z()) >> 4;
            if (container.inBounds(x, z)) {
                var plot = container.getPlot(x, z);
                SubLevel owner = plot == null ? null : plot.getSubLevel();
                if (owner != null && !owner.isRemoved()) {
                    Level parent = IplDimAgnostic.getParentLevel(owner);
                    if (parent != source && !IplDimAgnostic.isHostingLevel(parent)) {
                        return new Sample(parent,
                            owner.logicalPose().transformPosition(position, new Vector3d()));
                    }
                }
            }
        }
        Level parent = contextualLevel(source);
        return new Sample(parent == null ? source : parent, position);
    }
}