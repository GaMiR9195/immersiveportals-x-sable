package ipl.sable.mixin.client;

import ipl.sable.client.IplStraddleStaffPick;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.createmod.catnip.outliner.LineOutline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Portal-frame endpoints for Simulated's original noisy-node beam renderer. */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam",
    remap = false
)
public abstract class IplStaffBeamMixin {

    private static volatile java.lang.reflect.Field ipl$previousPosition;
    private static volatile java.lang.reflect.Field ipl$position;

    @Shadow(remap = false) private LineOutline line;
    @Shadow(remap = false) private List<?> nodes;
    @Shadow(remap = false) private double currentNodeRadius;

    /**
     * @author IPL-Sable
     * @reason Render in actual IP pass frame, not stock main-world frame.
     */
    @Overwrite(remap = false)
    private void render(
        Vec3 source, Vec3 target,
        com.mojang.blaze3d.vertex.PoseStack stack,
        SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    ) {
        // The owning handler passes a plot-coordinate end; use the same delayed endpoint that
        // its vanilla beam interpolation uses for the portal-frame lookup.
        Vec3 localAnchor = target;
        ClientSubLevel sub = Sable.HELPER.getContainingClient(localAnchor);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (sub != null) {
            net.minecraft.world.level.Level sourceLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()
                ? qouteall.imm_ptl.core.render.context_management.PortalRendering.getRenderingPortal().getDestinationWorld()
                : mc.level;
            java.util.List<qouteall.imm_ptl.core.portal.Portal> portals =
                sourceLevel == null ? java.util.List.of()
                    : IplStraddleStaffPick.getBeamPortalPath(sourceLevel, source, sub, localAnchor);
            target = IplStraddleStaffPick.mapBeamEndpoint(sub, localAnchor, partialTick, portals);
            java.util.List<qouteall.imm_ptl.core.portal.Portal> renderPath =
                IplStraddleStaffPick.getActiveRenderPath();
            for (qouteall.imm_ptl.core.portal.Portal portal : renderPath) {
                source = portal.transformPoint(source);
            }

            // The outer pass draws only up to the first aperture. IP renders the continuation
            // natively in each recursive portal pass with its own slot-0 stencil and clip plane.
            if (renderPath.isEmpty() && !portals.isEmpty()) {
                Vec3 hit = portals.get(0).rayTrace(source, target);
                if (hit != null) target = hit;
            }
        }
        Vec3 delta = target.subtract(source);
        Vec3 last = source;
        for (int i = 1; i < this.nodes.size(); i++) {
            Object node = this.nodes.get(i);
            Vec3 previous = ipl$nodePosition(node, "previousPosition");
            Vec3 current = ipl$nodePosition(node, "position");
            Vec3 offset = previous.lerp(current, partialTick);
            Vec3 point = source.add(delta.scale(i / (float) this.nodes.size())
                .add(offset.scale(this.currentNodeRadius)));
            this.line.set(last, point).render(stack, buffer, camera, partialTick);
            last = point;
        }
    }

    private static Vec3 ipl$nodePosition(Object node, String fieldName) {
        try {
            java.lang.reflect.Field field = fieldName.equals("previousPosition")
                ? ipl$previousPosition : ipl$position;
            if (field == null) {
                field = node.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                if (fieldName.equals("previousPosition")) {
                    ipl$previousPosition = field;
                } else {
                    ipl$position = field;
                }
            }
            return (Vec3) field.get(node);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Simulated PhysicsBeam node layout changed", exception);
        }
    }

}
