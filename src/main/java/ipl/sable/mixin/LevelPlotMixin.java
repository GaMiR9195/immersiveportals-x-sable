package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.transit.IplPieceTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block edits keep a ship's piece labels and per-piece material counts current. */
@Mixin(LevelPlot.class)
public abstract class LevelPlotMixin {

    @Inject(method = "onBlockChange", at = @At("TAIL"))
    private void ipl$trackPieces(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (((LevelPlot) (Object) this).getSubLevel() instanceof ServerSubLevel ship) {
            IplPieceTracker.onBlockChanged(ship, pos, state);
        }
    }
}
