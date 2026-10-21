package ipl.sable.mixin.client;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Typed access to Simulated's private node; no reflective setAccessible at runtime. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam$BeamNode", remap = false)
public interface IplPhysicsStaffBeamNodeAccessorMixin {
    @Accessor(value = "previousPosition", remap = false)
    Vec3 ipl$getPreviousPosition();
    @Accessor(value = "position", remap = false)
    Vec3 ipl$getPosition();
}
