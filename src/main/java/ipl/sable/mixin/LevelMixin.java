package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Write side of the projection plot bridge.
 *
 * <p>{@code SubLevelContainerMixin} lets a level read the plot of a ship that projects into it
 * from another dimension. A {@code setBlock} at those plot coordinates must not mutate the
 * other level's chunk with this level's neighbour updates, light engine and packet trackers
 * (duplicated or vanishing blocks on a cross-dimension image). On the server the write is
 * routed to the level that owns the plot, so every code path that still holds the wrong
 * level (buckets, explosions, block logic of other mods) edits the real ship. The client
 * never predicts into another level's plot: the server's block update is authoritative.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeForeignPlotWrite(
        BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir
    ) {
        Level self = (Level) (Object) this;
        SubLevelContainer container = SubLevelContainer.getContainer(self);
        if (container == null) return;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (!container.inBounds(chunkX, chunkZ)) return;
        LevelPlot plot = container.getPlot(chunkX, chunkZ);
        SubLevel owner = plot == null ? null : plot.getSubLevel();
        if (owner == null || owner.getLevel() == self) return;
        cir.setReturnValue(!self.isClientSide && owner.getLevel().setBlock(pos, state, flags, recursionLeft));
    }
}
