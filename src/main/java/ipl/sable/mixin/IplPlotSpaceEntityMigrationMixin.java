package ipl.sable.mixin;

import ipl.sable.dim.IplPlotEntityMigration;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detect entities moving INTO plot space while in a parent dimension and hand them to
 * {@link IplPlotEntityMigration} (which teleports them into the hosting dimension, where
 * plot coordinates are physically backed — see that class for the full rationale, e.g.
 * Simulated's launched plunger re-positioning itself into a hit ship's plot).
 *
 * <p>Hot-path discipline: {@code setPosRaw} runs for every entity movement, so the ONLY
 * work on the common path is two coordinate compares. The hosting plot grid starts at
 * block ~20.48M on both axes (plot-grid origin 10000 × 128-chunk plots); nothing legit in
 * a parent dimension lives past 16M, and the precise membership test (grid-range check on
 * the hosting container) runs only after this gate.
 */
@Mixin(Entity.class)
public abstract class IplPlotSpaceEntityMigrationMixin {

    @Inject(method = "setPosRaw(DDD)V", at = @At("TAIL"), require = 1)
    private void ipl$detectPlotSpaceEntry(double x, double y, double z, CallbackInfo ci) {
        if (x < 16_000_000.0 && z < 16_000_000.0 && x > -16_000_000.0 && z > -16_000_000.0) {
            return;
        }
        IplPlotEntityMigration.onPlotRangePosition((Entity) (Object) this, x, z);
    }
}
