package ipl.sable.mixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: the exact hit the plunger attaches with. The containing-probe timeline
 * showed the plunger sticking at WORLD coordinates (ship-side height) for under a
 * second, then releasing to the floor — meaning {@code onHitBlock} received a
 * world-frame hit for a visual ship hit. This logs, at the hit moment: the hit
 * location/blockPos (plot-frame ~20M vs world-frame distinguishes instantly), what
 * {@code getContaining} resolves for it, and which ships the clip overlay's own
 * intersection query ({@code getAllIntersecting}) reports around the ray — separating
 * "ship never intersected", "sanity check rejected", and "distance race lost".
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity",
    remap = false)
public abstract class IplPlungerHitProbeMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-plunger-hit");

    @Inject(method = "onHitBlock", at = @At("HEAD"), require = 0)
    private void ipl$logPlungerHit(BlockHitResult hit, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Vec3 loc = hit.getLocation();
        SubLevel containing = Sable.HELPER.getContaining(self.level(), loc);

        StringBuilder ships = new StringBuilder();
        BoundingBox3d probe = new BoundingBox3d(
            loc.subtract(4, 4, 4), loc.add(4, 4, 4));
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(self.level(), probe)) {
            if (ships.length() > 0) ships.append(", ");
            ships.append(sub.getUniqueId()).append(sub.isRemoved() ? "(removed)" : "");
        }

        IPL$LOG.info("[IPL-PLUNGER-HIT] side={} entityAt=({}, {}, {}) hitLoc=({}, {}, {}) "
            + "hitBlock={} containing={} nearbyShips=[{}]",
            self.level().isClientSide ? "CLIENT" : "SERVER",
            String.format("%.1f", self.getX()), String.format("%.1f", self.getY()),
            String.format("%.1f", self.getZ()),
            String.format("%.1f", loc.x), String.format("%.1f", loc.y),
            String.format("%.1f", loc.z),
            hit.getBlockPos(),
            containing == null ? "NULL" : containing.getUniqueId(),
            ships);
    }
}
