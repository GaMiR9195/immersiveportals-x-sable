package ipl.sable.mixin.client;

import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * Simulated's client staff handler: its beams, its private rotate-mode gate and its normal
 * release path (including the STOP_DRAG packet), without duplicating handler state.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public interface StaffClientHandlerAccessor {

    @Accessor(value = "beams")
    Object2ObjectMap<UUID, Object> ipl$getBeams();

    @Invoker(value = "isRotating")
    boolean ipl$isRotating();

    @Invoker(value = "stopDragging")
    void ipl$stopDragging();

    /** Continue the local drag on another client object of the same body (transit successor). */
    @Accessor(value = "dragSession")
    void ipl$setDragSession(PhysicsStaffClientHandler.ClientDragSession session);
}
