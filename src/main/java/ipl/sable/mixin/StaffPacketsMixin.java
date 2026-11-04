package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffDragPacket;
import ipl.sable.atlas.IplScenes;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * Native model: a staff packet acts on the handler of the level that OWNS the target
 * body. A player may grab a ship seen through a portal in another dimension; stock
 * Simulated would look the body up in the player's own level and silently do nothing.
 */
@Pseudo
@Mixin(
    targets = {
        "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffActionPacket",
        "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffDragPacket"
    },
    remap = false
)
public abstract class StaffPacketsMixin {

    @WrapOperation(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;get(Lnet/minecraft/server/level/ServerLevel;)Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;"
        )
    )
    private PhysicsStaffServerHandler ipl$routeToOwner(
        ServerLevel playerLevel,
        Operation<PhysicsStaffServerHandler> original
    ) {
        ServerLevel owner = IplScenes.ownerLevel(playerLevel.getServer(), ipl$subLevelId());
        return original.call(owner == null ? playerLevel : owner);
    }

    @Unique
    private UUID ipl$subLevelId() {
        Object self = this;
        return self instanceof PhysicsStaffDragPacket drag
            ? drag.subLevel()
            : ((StaffActionPacketAccessor) self).ipl$getSubLevel();
    }
}
