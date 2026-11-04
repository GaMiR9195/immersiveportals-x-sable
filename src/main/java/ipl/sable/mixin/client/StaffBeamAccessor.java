package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Endpoints, nodes and the draw call of Simulated's physics-staff beam (portal beam pass). */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam", remap = false)
public interface StaffBeamAccessor {

    @Accessor(value = "nodes")
    List<?> ipl$getNodes();

    @Accessor(value = "previousStart")
    Vec3 ipl$getPreviousStart();

    @Accessor(value = "start")
    Vec3 ipl$getStart();

    @Accessor(value = "previousEnd")
    Vec3 ipl$getPreviousEnd();

    @Accessor(value = "end")
    Vec3 ipl$getEnd();

    @Invoker(value = "render")
    void ipl$render(
        Vec3 start, Vec3 end, PoseStack poseStack, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    );
}
