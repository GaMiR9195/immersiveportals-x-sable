package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.duck.IplStaffDragSessionControl;
import ipl.sable.transit.IplGrabChain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Hook actual drag-session writes, after routing made {@code level} the body's level. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public abstract class StaffServerHandlerMixin {

    @Shadow private ServerLevel level;
    /**
     * The handler of the body's level may serve a player standing in another dimension
     * (grab through a portal); resolve the player server-wide instead of dropping the session.
     */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;")
    )
    private Player ipl$serverWidePlayer(
        ServerLevel level, UUID id,
        Operation<Player> original
    ) {
        Player player = original.call(level, id);
        return player != null || level.getServer() == null ? player : level.getServer().getPlayerList().getPlayer(id);
    }

    @Inject(method = "drag", at = @At("HEAD"))
    private void ipl$beginFrame(
        UUID playerId, UUID subId, Vector3dc playerRelativeGoal, Vector3dc localAnchor,
        Quaterniondc orientation, CallbackInfo ci
    ) {
        ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) IplGrabChain.begin(player, subId);
    }

    /**
     * A body transit is server-initiated: drag packets the client composed BEFORE it
     * processed the ordered rebase RPC still carry the pre-transit orientation and would
     * stomp the server-side reframe for a tick. Premultiply those packets by the pending
     * transit rotations until the client acknowledges — exact via TCP ordering.
     */
    @ModifyVariable(method = "drag", at = @At("HEAD"), argsOnly = true)
    private Quaterniondc ipl$compensateUnackedTransits(
        Quaterniondc orientation,
        UUID playerId, UUID subId, Vector3dc playerRelativeGoal, Vector3dc localAnchor,
        Quaterniondc orientationArg
    ) {
        return IplGrabChain.adjustIncomingOrientation(playerId, new Quaterniond(orientation));
    }

    /**
     * After a cross-dimension move landed in a different plot slot, packets composed before
     * the client re-bound its session still name the old plot-local anchor (see
     * {@link IplStaffDragSessionControl#ipl$mapIncomingAnchor}).
     */
    @ModifyVariable(method = "drag", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private Vector3dc ipl$compensateMovedPlot(
        Vector3dc localAnchor,
        UUID playerId, UUID subId, Vector3dc playerRelativeGoal, Vector3dc localAnchorArg,
        Quaterniondc orientation
    ) {
        IplStaffDragSessionControl session = this.ipl$session(playerId);
        return session == null ? localAnchor : session.ipl$mapIncomingAnchor(localAnchor);
    }

    @Inject(method = "stopDragging", at = @At("HEAD"))
    private void ipl$endFrame(UUID playerId, CallbackInfo ci) {
        IplStaffDragSessionControl session = this.ipl$session(playerId);
        if (session != null) session.ipl$removeConstraint();
        IplGrabChain.end(this.level.getServer(), playerId);
    }

    @Unique
    @Nullable
    private IplStaffDragSessionControl ipl$session(UUID playerId) {
        Object session = ((StaffServerHandlerAccessor) (Object) this).ipl$getDraggingSessions().get(playerId);
        return session instanceof IplStaffDragSessionControl control ? control : null;
    }

}
