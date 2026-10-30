package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
public abstract class IplPhysicsStaffHandlerRoutingMixin {

    @Unique
    private static java.lang.reflect.Field ipl$actionSubField;

    @WrapOperation(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;get(Lnet/minecraft/server/level/ServerLevel;)Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;",
            remap = false
        ),
        require = 0
    )
    private dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler ipl$routeToOwner(
        ServerLevel playerLevel,
        Operation<dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler> original
    ) {
        ServerLevel owner = ipl.sable.atlas.IplScenes.ownerLevel(playerLevel.getServer(), ipl$subLevelId());
        return original.call(owner == null ? playerLevel : owner);
    }

    @Unique
    private UUID ipl$subLevelId() {
        Object self = this;
        try {
            if (self instanceof dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffDragPacket drag) {
                return drag.subLevel();
            }
            java.lang.reflect.Field field = ipl$actionSubField;
            if (field == null) {
                field = dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffActionPacket.class
                    .getDeclaredField("subLevel");
                field.setAccessible(true);
                ipl$actionSubField = field;
            }
            return field.get(self) instanceof UUID id ? id : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
