package ipl.sable.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Exposes active sessions only for atomic portal-frame handoff. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public interface StaffServerHandlerAccessor {
    @Accessor(value = "draggingSessions")
    Map<UUID, Object> ipl$getDraggingSessions();

    /** Resend this level's drag-session list to clients on the next handler tick. */
    @Accessor(value = "draggingSessionsDirty")
    void ipl$setDraggingSessionsDirty(boolean dirty);
}
