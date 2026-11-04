package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.transit.IplAtlasStraddleSession;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Контейнер саб-уровней: чтение участка корабля из другого измерения на время проекции и
 * выдача участков, свободных сразу во всех измерениях.
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class SubLevelContainerMixin {

    // ---- Мост участков для проекций ----
    // PROJECTION PLOT BRIDGE — lets a level READ the plot of a ship that lives in another
    // dimension while that ship projects into it through a portal.
    //
    // <p>Sable's gameplay code (entity↔ship collision, raycasts, {@code getContaining}, block
    // reads at plot coordinates) transforms positions into plot space and then reads through the
    // CURRENT level's container. A ship straddling a cross-dimension portal is native to its own
    // level; its far-side image is collided with and picked from the destination level, whose
    // grid has no plot at those coordinates. On a {@code getPlot} miss inside the grid bounds,
    // this mixin resolves the plot from the other levels — plot slots are allocated server-wide
    // unique ({@code SubLevelContainerMixin}), so the owner is unambiguous — and returns it
    // only while that owner has a portal image INTO this level.
    //
    // <p>Reads only: {@code LevelMixin} rejects block writes at plot
    // coordinates owned by another level, so placing/breaking through a cross-dimension image
    // can never duplicate or desync blocks. By-UUID lookups are deliberately NOT bridged
    // (container bookkeeping during a dimension change must not see the other instance).

    /** Re-entrancy guard: the owner search itself calls {@code getPlot} on other containers. */
    @Unique
    private static final ThreadLocal<boolean[]> IPL$BRIDGING = ThreadLocal.withInitial(() -> new boolean[1]);

    @Shadow
    public abstract Level getLevel();

    @Shadow
    public abstract boolean inBounds(int chunkX, int chunkZ);

    @ModifyReturnValue(
        method = "getPlot(II)Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;",
        at = @At("RETURN"),
        require = 1
    )
    private LevelPlot ipl$bridgePlotByChunkXZ(LevelPlot original, int chunkX, int chunkZ) {
        return original != null ? original : ipl$projectedPlot(chunkX, chunkZ);
    }

    @ModifyReturnValue(
        method = "getPlot(Lnet/minecraft/world/level/ChunkPos;)Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;",
        at = @At("RETURN"),
        require = 1
    )
    private LevelPlot ipl$bridgePlotByChunkPos(LevelPlot original, ChunkPos pos) {
        return original != null ? original : ipl$projectedPlot(pos.x, pos.z);
    }

    @Unique
    private LevelPlot ipl$projectedPlot(int chunkX, int chunkZ) {
        if (!this.inBounds(chunkX, chunkZ)) return null; // ordinary world chunk: hot path
        Level self = this.getLevel();
        if (self == null) return null;
        boolean client = self.isClientSide;
        if (client ? IplStraddleSessionStore.isIdle() : IplAtlasStraddleSession.isIdle()) {
            return null;
        }
        boolean[] guard = IPL$BRIDGING.get();
        if (guard[0]) return null;
        guard[0] = true;
        try {
            Iterable<? extends Level> levels = client
                ? IplClientProjections.clientLevels()
                : self instanceof ServerLevel server && server.getServer() != null
                    ? server.getServer().getAllLevels() : List.<Level>of();
            for (Level other : levels) {
                if (other == self) continue;
                SubLevelContainer container = SubLevelContainer.getContainer(other);
                if (container == null || container == (Object) this) continue;
                LevelPlot plot = container.getPlot(chunkX, chunkZ);
                if (plot == null) continue;
                SubLevel owner = plot.getSubLevel();
                // Slots are unique: once the owner is found the answer is final.
                return owner != null && !owner.isRemoved()
                    && IplStraddlePoseMap.getMappingInto(owner, self) != null ? plot : null;
            }
            return null;
        } finally {
            guard[0] = false;
        }
    }

    /**
     * Removals route to the container that OWNS the sub-level: command paths may call
     * {@code getContainer(sourceLevel).removeSubLevel(ship, ...)} for a ship resolved
     * elsewhere — removing from the wrong container is a no-op that leaves the ship alive.
     * Same-owner calls pass through untouched.
     */
    @Inject(
        method = "removeSubLevel(Ldev/ryanhcode/sable/sublevel/SubLevel;"
            + "Ldev/ryanhcode/sable/sublevel/storage/SubLevelRemovalReason;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeRemoveToOwningContainer(
        SubLevel subLevel,
        SubLevelRemovalReason reason,
        CallbackInfo ci
    ) {
        Level self = this.getLevel();
        if (self == null || subLevel == null || subLevel.getLevel() == self) return;
        SubLevelContainer owner = SubLevelContainer.getContainer(subLevel.getLevel());
        if (owner == null || owner == (Object) this) return;
        owner.removeSubLevel(subLevel, reason);
        ci.cancel();
    }

    // ---- Уникальные участки во всех измерениях ----
    // UNION-FREE PLOT ALLOCATION — globally unique plot slots across dimensions.
    //
    // <p>Native model: every level keeps its own plot grid, so two dimensions would
    // otherwise hand out the same slot (stock {@code getFirstEmptyPlot} starts at local
    // (0,0) everywhere). A server container therefore picks the first slot that is free in
    // EVERY server level's grid with identical geometry. Consequences:
    // <ul>
    // <li>a cross-dimension move can keep the ship's slot, so every persisted
    // plot-coordinate reference (constraint anchors, Simulated plate positions,
    // portal anchors) survives the move;</li>
    // <li>a plot coordinate names at most one live ship server-wide, which keeps the
    // projection plot bridge and the projected-plot write guard unambiguous.</li>
    // </ul>
    // Containers with different grid geometry are skipped (stock behavior for them).

    @Inject(method = "getFirstEmptyPlot", at = @At("HEAD"), cancellable = true)
    private void ipl$firstSlotFreeInAllGrids(CallbackInfoReturnable<Vector2i> cir) {
        if (!(this.getLevel() instanceof ServerLevel self)) return; // client never allocates fresh slots
        SubLevelContainer mine = (SubLevelContainer) (Object) this;
        SubLevelContainerAccessor mineOrigin = (SubLevelContainerAccessor) mine;
        List<BitSet> others = new ArrayList<>(4);
        for (ServerLevel level : self.getServer().getAllLevels()) {
            SubLevelContainer other = SubLevelContainer.getContainer(level);
            if (other == null || other == mine) continue;
            // Identical grid geometry is required for index-aligned occupancy comparison.
            if (other.getLogSideLength() != mine.getLogSideLength()
                || ((SubLevelContainerAccessor) other).ipl$originX() != mineOrigin.ipl$originX()
                || ((SubLevelContainerAccessor) other).ipl$originZ() != mineOrigin.ipl$originZ()) {
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
