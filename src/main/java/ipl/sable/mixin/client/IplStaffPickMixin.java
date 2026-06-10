package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Physics-staff targeting on straddled through-parts.
 *
 * <p>The staff resolves its target via {@code player.pick(...)} — Sable's raycast overlay
 * uses unmapped poses, so the through-portal part of a straddling ship can't be targeted
 * from the dest side (recast-from-hand fails). Wrap every pick in the staff's client
 * handler: when the normal pick misses, raycast the straddle projections with MAPPED
 * poses ({@link IplStraddleStaffPick}) and return the plot-coordinate hit, which flows
 * through the staff's existing sub-level resolution (plot-bridge) unchanged.
 *
 * <p>First cut: the mapped pick only engages on a vanilla MISS (aiming at the
 * through-part with terrain behind it picks the terrain — frame-mixed distance tie-break
 * deferred).
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class IplStaffPickMixin {

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        ),
        require = 0
    )
    private HitResult ipl$pickThroughParts(
        Player player, double range, float partialTick, boolean fluids,
        Operation<HitResult> original
    ) {
        HitResult vanilla = original.call(player, range, partialTick, fluids);
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS) {
            return vanilla;
        }
        HitResult mapped = IplStraddleStaffPick.pickStraddleProjections(player, range, partialTick);
        return mapped != null ? mapped : vanilla;
    }
}
