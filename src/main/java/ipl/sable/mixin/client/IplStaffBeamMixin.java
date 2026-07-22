package ipl.sable.mixin.client;

import ipl.sable.client.IplStaffPortalBeamRenderer;
import ipl.sable.client.IplStraddleStaffPick;
import net.createmod.catnip.outliner.LineOutline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Draw-only half of Simulated's noisy-node beam renderer. */
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
        if (!IplStaffPortalBeamRenderer.isPhysicalBeamPass()
            || IplStaffPortalBeamRenderer.getActiveSegment() == null) return;

        IplStraddleStaffPick.BeamSegment segment = IplStaffPortalBeamRenderer.getActiveSegment();
        int nodeCount = this.nodes.size();
        if (nodeCount < 2 || segment.totalLength() <= 1.0e-9) {
            this.line.set(source, target).render(stack, buffer, camera, partialTick);
            return;
        }

        // Preserve Simulated's one noisy path through every portal. A half starts/ends at an
        // exact aperture point, but all its interior nodes retain their original global phase.
        // Far-side noise vectors rotate through preceding portals with the path itself.
        Vec3 last = source;
        int firstNode = Math.max(1, (int) Math.ceil(segment.startFraction() * nodeCount));
        int lastNode = Math.min(nodeCount - 1, (int) Math.floor(segment.endFraction() * nodeCount));
        for (int node = firstNode; node <= lastNode; node++) {
            double fraction = node / (double) nodeCount;
            if (fraction <= segment.startFraction() || fraction >= segment.endFraction()) continue;
            Vec3 point = ipl$pointAt(segment, fraction, partialTick);
            this.line.set(last, point).render(stack, buffer, camera, partialTick);
            last = point;
        }
        this.line.set(last, target).render(stack, buffer, camera, partialTick);
    }

    private Vec3 ipl$pointAt(IplStraddleStaffPick.BeamSegment segment, double fraction, float partialTick) {
        double span = segment.endFraction() - segment.startFraction();
        double local = span <= 1.0e-9 ? 0.0 : (fraction - segment.startFraction()) / span;
        Vec3 base = segment.start().lerp(segment.end(), Math.clamp(local, 0.0, 1.0));
        Vec3 noise = ipl$noiseAt(fraction, partialTick);
        for (qouteall.imm_ptl.core.portal.Portal portal : segment.prefix()) {
            noise = portal.transformLocalVecNonScale(noise);
        }
        return base.add(noise);
    }

    private Vec3 ipl$noiseAt(double fraction, float partialTick) {
        double scaled = Math.clamp(fraction, 0.0, 1.0) * this.nodes.size();
        int left = (int) Math.floor(scaled);
        int right = Math.min(this.nodes.size(), left + 1);
        Vec3 a = ipl$nodeOffset(left, partialTick);
        Vec3 b = ipl$nodeOffset(right, partialTick);
        return a.lerp(b, scaled - left).scale(this.currentNodeRadius);
    }

    private Vec3 ipl$nodeOffset(int index, float partialTick) {
        if (index <= 0 || index >= this.nodes.size()) return Vec3.ZERO;
        Object node = this.nodes.get(index);
        return ipl$nodePosition(node, "previousPosition").lerp(
            ipl$nodePosition(node, "position"), partialTick);
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
