package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replace Simulated's one-world pick at source. The handler receives the exact plot-coordinate
 * hit Sable expects, while the capture state records portal chain before session construction.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class IplStaffPickMixin {

    @WrapOperation(
        method = "onItemUsed",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        ),
        require = 0
    )
    private HitResult ipl$portalPick(
        LocalPlayer player, double range, float tickDelta, boolean fluids, Operation<HitResult> original
    ) {
        IplStraddleStaffPick.PortalTarget through =
            IplStraddleStaffPick.pickThroughPortals(player, range, tickDelta);
        if (through != null) return through.hit();

        HitResult local = original.call(player, range, tickDelta, fluids);
        HitResult projection = IplStraddleStaffPick.pickStraddleProjections(player, range, tickDelta);
        if (projection != null) return projection;
        IplStraddleStaffPick.rememberLocalProjection(player, local);
        return local;
    }

    @Inject(method = "startDraggingSubLevel", at = @At("HEAD"), require = 0)
    private void ipl$capturePortalPath(
        SubLevel sub, BlockPos blockPos, LocalPlayer player, InteractionHand hand, CallbackInfo ci
    ) {
        if (sub instanceof ClientSubLevel clientSub) {
            IplStraddleStaffPick.beginDrag(clientSub);
        }
    }

    @Inject(method = "stopDragging", at = @At("HEAD"), require = 0)
    private void ipl$clearCaptureBeforeStop(CallbackInfo ci) {
        IplStraddleStaffPick.clearDragTargets();
    }
}
