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
 * Write guard for the projection plot bridge (server and client).
 *
 * <p>Server writes are ROUTED to the owning level (see below); client writes are rejected.
 *
 * <p>{@code IplPlotBridgeMixin} lets a level read the plot of a ship that projects into it
 * from another dimension. Writes must never take that path: a {@code setBlock} at those plot
 * coordinates would mutate the other level's chunk with this level's neighbour updates,
 * light engine and packet trackers — the source of duplicated or vanishing blocks when
 * placing/breaking on a cross-dimension image. Such writes are rejected (return {@code false},
 * exactly like a refused placement); the ship's own level edits it normally, and a
 * same-dimension image is the ship itself, so it stays editable.
 */
@Mixin(Level.class)
public abstract class IplForeignPlotWriteGuardMixin {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$rejectForeignPlotWrite(
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
        if (owner != null && owner.getLevel() != self) {
            Level own = owner.getLevel();
            if (!self.isClientSide && own != null && !own.isClientSide) {
                // SERVER: the write goes to the level that owns the plot, with that level's
                // neighbour updates, light engine, block entities and chunk trackers -- any
                // code path that still holds the wrong level (buckets, explosions, block
                // logic of other mods) edits the real ship instead of being dropped.
                // Русский комментарий: раньше такая запись просто отклонялась, и всё, что
                // шло не через маршрутизацию по владельцу, на проекции "не работало".
                cir.setReturnValue(own.setBlock(pos, state, flags, recursionLeft));
            } else {
                // CLIENT: never predict into another level's plot; the server's block update
                // for the owner level is authoritative.
                cir.setReturnValue(false);
            }
        }
    }
}
