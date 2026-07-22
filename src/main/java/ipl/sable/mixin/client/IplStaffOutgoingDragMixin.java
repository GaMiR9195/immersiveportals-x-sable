package ipl.sable.mixin.client;

import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffDragPacket;
import foundry.veil.api.network.VeilPacketManager;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Simulated's packet has no dimension or portal route. Replace its private packet writer so the
 * one value sent to server is already in body-parent frame. No post-packet cursor inference.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class IplStaffOutgoingDragMixin {

    @Shadow(remap = false) private PhysicsStaffClientHandler.ClientDragSession dragSession;

    /**
     * @author IPL-Sable
     * @reason Stock packet loses portal-frame identity; encode mapped target before network.
     */
    @Overwrite(remap = false)
    private void sendDraggingData(Player player) {
        PhysicsStaffClientHandler.ClientDragSession session = this.dragSession;
        if (session == null) return;

        net.minecraft.world.phys.Vec3 look = player.getLookAngle();
        Vector3d rawGoal = new Vector3d(look.x, look.y, look.z).mul(session.distance());
        Vector3d bodyFrameGoal = IplStraddleStaffPick.mapOutgoingDragGoal(
            player, session.dragSubLevel().getUniqueId(), rawGoal
        );
        VeilPacketManager.server().sendPacket(new PhysicsStaffDragPacket(
            session.dragSubLevel().getUniqueId(), bodyFrameGoal,
            session.dragLocalAnchor(), session.dragOrientation()
        ));
    }
}
