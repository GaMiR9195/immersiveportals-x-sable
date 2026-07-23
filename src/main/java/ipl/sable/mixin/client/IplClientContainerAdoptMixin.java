package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.duck.IplClientContainerAdoptDuck;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Container bookkeeping surgery for the client parent-flip adopt
 * ({@link ipl.sable.client.IplClientAdopt}): insert/remove an existing sub-level without
 * triggering Sable's allocation observers (which would create physics state, fire
 * render-data creation, etc.) or the removal cascade (which would destroy the plot's
 * chunks and render data).
 *
 * <p>Plot slot indices are identical across containers: every container is built from the
 * same config (origin, side length, plot size), and hosted plot slots come from the single
 * hosting-dimension allocator.
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplClientContainerAdoptMixin implements IplClientContainerAdoptDuck {

    @Shadow @Final protected SubLevel[] subLevels;
    @Shadow(remap = false) @Final private List<SubLevel> allSubLevels;
    @Shadow(remap = false) @Final private Map<UUID, SubLevel> subLevelsByUUID;
    @Shadow(remap = false) @Final private BitSet occupancy;
    @Shadow(remap = false) @Final private int logSideLength;

    @Shadow
    public abstract Vector2i getOrigin();

    private int ipl$slotIndexOf(SubLevel sub) {
        ChunkPos plotPos = sub.getPlot().plotPos;
        Vector2i origin = this.getOrigin();
        int localX = plotPos.x - origin.x;
        int localZ = plotPos.z - origin.y;
        return localX + (localZ << this.logSideLength);
    }

    @Override
    public void ipl$detachKeepingState(SubLevel sub) {
        int index = ipl$slotIndexOf(sub);
        if (index >= 0 && index < this.subLevels.length && this.subLevels[index] == sub) {
            this.subLevels[index] = null;
            this.occupancy.clear(index);
        }
        this.allSubLevels.remove(sub);
        this.subLevelsByUUID.remove(sub.getUniqueId(), sub);
    }

    @Override
    public void ipl$attachExisting(SubLevel sub) {
        int index = ipl$slotIndexOf(sub);
        if (index < 0 || index >= this.subLevels.length) {
            throw new IllegalStateException("plot slot out of range for adopt: " + index);
        }
        SubLevel occupant = this.subLevels[index];
        if (occupant != null && occupant != sub) {
            throw new IllegalStateException(
                "plot slot " + index + " already occupied by " + occupant.getUniqueId());
        }
        this.subLevels[index] = sub;
        this.occupancy.set(index);
        if (!this.allSubLevels.contains(sub)) {
            this.allSubLevels.add(sub);
        }
        this.subLevelsByUUID.put(sub.getUniqueId(), sub);
    }
}
