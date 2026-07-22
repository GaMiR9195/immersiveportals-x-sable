package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplStaffPortalDragState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Hook actual drag-session writes, after routing made `level` the hosting level. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public abstract class IplStaffServerStateMixin {

    @Shadow(remap = false) private ServerLevel level;

    @Inject(method = "drag", at = @At("HEAD"), require = 0)
    private void ipl$beginFrame(
        UUID playerId, UUID subId, Vector3dc playerRelativeGoal, Vector3dc localAnchor,
        Quaterniondc orientation, CallbackInfo ci
    ) {
        ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) IplStaffPortalDragState.begin(player, subId, localAnchor);
    }

    @Inject(method = "stopDragging", at = @At("HEAD"), require = 0)
    private void ipl$endFrame(UUID playerId, CallbackInfo ci) {
        IplStaffPortalDragState.end(playerId);
    }
}
