package ipl.sable.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/** The target body of a staff action packet (the field has no public getter). */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffActionPacket", remap = false)
public interface StaffActionPacketAccessor {
    @Accessor(value = "subLevel")
    UUID ipl$getSubLevel();
}
