package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.world.level.Level;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;

/**
 * UNION-FREE PLOT ALLOCATION — globally unique plot slots across dimensions.
 *
 * <p>Native model: every level keeps its own plot grid, so two dimensions would
 * otherwise hand out the same slot (stock {@code getFirstEmptyPlot} starts at local
 * (0,0) everywhere). A server container therefore picks the first slot that is free in
 * EVERY server level's grid with identical geometry. Consequences:
 * <ul>
 *   <li>a cross-dimension move can keep the ship's slot, so every persisted
 *       plot-coordinate reference (constraint anchors, Simulated plate positions,
 *       portal anchors) survives the move;</li>
 *   <li>a plot coordinate names at most one live ship server-wide, which keeps the
 *       projection plot bridge and the projected-plot write guard unambiguous.</li>
 * </ul>
 * Containers with different grid geometry are skipped (stock behavior for them).
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplUnionPlotAllocationMixin {

    @Shadow
    public abstract Level getLevel();

    @Inject(method = "getFirstEmptyPlot", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$firstSlotFreeInAllGrids(CallbackInfoReturnable<Vector2i> cir) {
        if (!(this.getLevel() instanceof net.minecraft.server.level.ServerLevel self) || self.getServer() == null) {
            return; // client never allocates fresh slots
        }
        SubLevelContainer mine = (SubLevelContainer) (Object) this;
        IplSubLevelContainerOriginAccessor mineOrigin = (IplSubLevelContainerOriginAccessor) mine;
        java.util.List<BitSet> others = new java.util.ArrayList<>(4);
        for (net.minecraft.server.level.ServerLevel level : self.getServer().getAllLevels()) {
            SubLevelContainer other = SubLevelContainer.getContainer(level);
            if (other == null || other == mine) continue;
            // Identical grid geometry is required for index-aligned occupancy comparison.
            if (other.getLogSideLength() != mine.getLogSideLength()
                || ((IplSubLevelContainerOriginAccessor) other).ipl$originX() != mineOrigin.ipl$originX()
                || ((IplSubLevelContainerOriginAccessor) other).ipl$originZ() != mineOrigin.ipl$originZ()) {
                continue;
            }
            others.add(other.getOccupancy());
        }
        if (others.isEmpty()) return;

        int side = 1 << mine.getLogSideLength();
        BitSet ours = mine.getOccupancy();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                int index = mine.getIndex(x, z);
                if (ours.get(index)) continue;
                boolean free = true;
                for (BitSet theirs : others) {
                    if (theirs.get(index)) { free = false; break; }
                }
                if (free) {
                    cir.setReturnValue(new Vector2i(x, z));
                    return;
                }
            }
        }
        // Union exhausted — let stock pick a self-free slot.
    }
}
