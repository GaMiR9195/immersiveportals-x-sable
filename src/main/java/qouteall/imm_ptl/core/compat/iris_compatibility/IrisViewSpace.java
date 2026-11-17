package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * The camera entity as seen from the view being rendered.
 *
 * <p>Portal views move only the camera; the player stays in its own world. Uniforms that packs
 * derive from the player (eye brightness, biome, sky color, eye altitude, eye position) are read
 * at the player's position carried through the portals of the view, in the view's world, so a
 * pack's fog and lighting behind a portal match that side.
 *
 * <p>Holds no Iris types.
 */
public final class IrisViewSpace {
    private static final double DESTINATION_MARGIN = 0.5;

    private IrisViewSpace() {}

    public static double eyeY(Entity entity, double eyeY) {
        if (!PortalRendering.isRendering()) return eyeY;
        return toView(new Vec3(entity.getX(), eyeY, entity.getZ())).y;
    }

    public static BlockPos blockPosition(Entity entity, BlockPos blockPosition) {
        if (!PortalRendering.isRendering()) return blockPosition;
        return BlockPos.containing(toView(entity.position()));
    }

    public static Level level(Level level) {
        if (!PortalRendering.isRendering()) return level;
        Level viewLevel = Minecraft.getInstance().level;
        return viewLevel == null ? level : viewLevel;
    }

    /**
     * The position carried through each portal of the view. A carried camera usually lands behind
     * the destination plane, inside the terrain around the other portal; it is moved just in front
     * of that plane so light and biome are sampled where the player would come out.
     */
    public static Vec3 toView(Vec3 position) {
        if (!PortalRendering.isRendering()) return position;
        for (Portal portal : PortalRendering.getPortalPath()) {
            position = portal.transformPoint(position);
            Vec3 content = portal.getContentDirection();
            double depth = position.subtract(portal.getDestPos()).dot(content);
            if (depth < DESTINATION_MARGIN) {
                position = position.add(content.scale(DESTINATION_MARGIN - depth));
            }
        }
        return position;
    }
}
