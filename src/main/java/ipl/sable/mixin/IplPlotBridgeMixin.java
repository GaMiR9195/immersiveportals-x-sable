package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.transit.IplAtlasStraddleSession;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PROJECTION PLOT BRIDGE — lets a level READ the plot of a ship that lives in another
 * dimension while that ship projects into it through a portal.
 *
 * <p>Sable's gameplay code (entity↔ship collision, raycasts, {@code getContaining}, block
 * reads at plot coordinates) transforms positions into plot space and then reads through the
 * CURRENT level's container. A ship straddling a cross-dimension portal is native to its own
 * level; its far-side image is collided with and picked from the destination level, whose
 * grid has no plot at those coordinates. On a {@code getPlot} miss inside the grid bounds,
 * this mixin resolves the plot from the other levels — plot slots are allocated server-wide
 * unique ({@code IplUnionPlotAllocationMixin}), so the owner is unambiguous — and returns it
 * only while that owner has a portal image INTO this level.
 *
 * <p>Reads only: {@code IplForeignPlotWriteGuardMixin} rejects block writes at plot
 * coordinates owned by another level, so placing/breaking through a cross-dimension image
 * can never duplicate or desync blocks. By-UUID lookups are deliberately NOT bridged
 * (container bookkeeping during a dimension change must not see the other instance).
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplPlotBridgeMixin {

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
        if (client ? ipl.sable.client.IplStraddleSessionStore.isIdle() : IplAtlasStraddleSession.isIdle()) {
            return null;
        }
        boolean[] guard = IPL$BRIDGING.get();
        if (guard[0]) return null;
        guard[0] = true;
        try {
            Iterable<? extends Level> levels = client
                ? ipl.sable.client.IplClientProjections.clientLevels()
                : self instanceof ServerLevel server && server.getServer() != null
                    ? server.getServer().getAllLevels() : java.util.List.<Level>of();
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
    @org.spongepowered.asm.mixin.injection.Inject(
        method = "removeSubLevel(Ldev/ryanhcode/sable/sublevel/SubLevel;"
            + "Ldev/ryanhcode/sable/sublevel/storage/SubLevelRemovalReason;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipl$routeRemoveToOwningContainer(
        SubLevel subLevel,
        dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason reason,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        Level self = this.getLevel();
        if (self == null || subLevel == null || subLevel.getLevel() == self) return;
        SubLevelContainer owner = SubLevelContainer.getContainer(subLevel.getLevel());
        if (owner == null || owner == (Object) this) return;
        owner.removeSubLevel(subLevel, reason);
        ci.cancel();
    }
}
