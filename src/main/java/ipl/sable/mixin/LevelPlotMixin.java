package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.transit.IplPortalVolumeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not rescan a construction every tick; invalidate its full-block cache only on edits. */
@Mixin(LevelPlot.class)
public abstract class LevelPlotMixin {

    @Inject(method = "onBlockChange", at = @At("TAIL"))
    private void ipl$invalidatePortalVolume(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (((LevelPlot) (Object) this).getSubLevel() instanceof ServerSubLevel ship) {
            IplPortalVolumeCache.invalidate(ship);
        }
    }
}
