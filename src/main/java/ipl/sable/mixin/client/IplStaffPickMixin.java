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
            // The staff's variable is typed LocalPlayer, so the INVOKE owner in bytecode
            // is LocalPlayer (inherited method or not) — a Player-owner target never
            // matches (spec §20.0 item 8 receiver-type rule).
            target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        ),
        require = 0
    )
    private HitResult ipl$pickThroughParts(
        net.minecraft.client.player.LocalPlayer player, double range, float partialTick, boolean fluids,
        Operation<HitResult> original
    ) {
        HitResult vanilla = original.call(player, range, partialTick, fluids);
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS) {
            return vanilla;
        }
        HitResult mapped = IplStraddleStaffPick.pickStraddleProjections(player, range, partialTick);
        return mapped != null ? mapped : vanilla;
    }

    /**
     * Frame-correct staff visuals/anchors: every {@code SubLevel.logicalPose()} read in the
     * staff's client handler (beam endpoints, lock anchors, particles — all of which
     * transform plot-local hit locations back to world space) substitutes the portal-mapped
     * pose for foreign straddlers, so the lock/beam appears AT the through-part rather than
     * at the source-frame position ~135 blocks away.
     */
    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;",
            remap = false
        ),
        require = 0
    )
    private dev.ryanhcode.sable.companion.math.Pose3d ipl$mapStaffPose(
        dev.ryanhcode.sable.sublevel.SubLevel sub,
        Operation<dev.ryanhcode.sable.companion.math.Pose3d> original
    ) {
        dev.ryanhcode.sable.companion.math.Pose3d pose = original.call(sub);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return pose;
        net.minecraft.core.BlockPos offset =
            ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, mc.level);
        return offset != null ? ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset) : pose;
    }

    /** Same mapping for interpolated render poses (drag beam endpoint uses renderPose(pt)). */
    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            remap = false
        ),
        require = 0
    )
    private dev.ryanhcode.sable.companion.math.Pose3dc ipl$mapStaffRenderPose(
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub, float pt,
        Operation<dev.ryanhcode.sable.companion.math.Pose3dc> original
    ) {
        dev.ryanhcode.sable.companion.math.Pose3dc pose = original.call(sub, pt);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return pose;
        net.minecraft.core.BlockPos offset =
            ipl.sable.transit.IplStraddlePoseMap.getOffsetInto(sub, mc.level);
        return offset != null ? ipl.sable.transit.IplStraddlePoseMap.mapped(pose, offset) : pose;
    }
}
