package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Who "the players of the storage dimension" are.
 *
 * <p>No player stands in {@code ipl_sable:sublevels}; its content is seen from the worlds its
 * ships fly through. A dimension-wide delivery addressed to storage therefore belongs to the
 * players of those worlds: exactly the armed world when hosted code runs in a frame,
 * otherwise every world that currently parents a live hosted ship. No player is guessed from
 * proximity; worlds without hosted ships receive nothing.
 */
public final class IplStorageAudience {
    private IplStorageAudience() {}

    public static Set<ResourceKey<Level>> viewingWorlds(ServerLevel storage) {
        Set<ResourceKey<Level>> worlds = new HashSet<>();
        if (!IplDimAgnostic.isHostingLevel(storage)) return worlds;
        ServerLevel frame = IplWorldFrameContext.current();
        if (frame != null && frame != storage && !IplDimAgnostic.isHostingLevel(frame)) {
            worlds.add(frame.dimension());
            return worlds;
        }
        SubLevelContainer container = SubLevelContainer.getContainer((Level) storage);
        if (container == null) return worlds;
        for (SubLevel sub : IplOwnedSubLevels.owned(container)) {
            if (sub == null || sub.isRemoved()) continue;
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
            if (parent != null) worlds.add(parent.dimension());
        }
        return worlds;
    }
}
