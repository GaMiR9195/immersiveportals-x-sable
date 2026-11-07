package ipl.sable.render;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplStraddleRenderCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

/**
 * Visibility of a sub-level draw: Sable's vanilla/reach-around {@code updateCulling} does not
 * cull, so hidden successors and off-frustum bodies are rejected here. {@link #inFrustum} also answers Create: Pocket Sized's own
 * frustum filter ({@code PocketRenderFrameMixin}).
 */
public final class IplSubLevelRenderVisibility {

    private IplSubLevelRenderVisibility() {}

    public static boolean hasBlockEntities(ClientSubLevel sub) {
        var data = sub.getRenderData();
        if (data instanceof VanillaChunkedSubLevelRenderData chunks) {
            for (var section : chunks.allRenderSections()) {
                if (!section.getCompiled().getRenderableBlockEntities().isEmpty()) return true;
            }
            // Глобальные BE (верёвки, лебёдки) лежат не в renderable-списках, а в
            // RenderSection.globalBlockEntities; BE, визуализируемые Flywheel, из списков
            // секций исключены вовсе. Без этой проверки проход BE саб-уровня с одной лишь
            // верёвкой или шестернёй пропускался бы целиком.
            return IplSubLevelBlockEntityPass.hasExtraBlockEntities(sub);
        }
        if (data instanceof VanillaSingleSubLevelRenderData single) {
            return single.getRenderBlockEntity() != null;
        }
        return true; // A custom backend can have its own BE storage.
    }

    public static boolean visible(ClientSubLevel sub, Pose3dc pose) {
        // A moved body's successor stays hidden until its transit ghost hands over.
        if (IplParentDimSync.isSuppressedSuccessor(sub)) return false;
        // The main-view frustum is not the light-view frustum. Shadow rendering can
        // reuse this pass and pose; never read/write its camera visibility cache.
        if (IrisInterface.invoker.isRenderingShadowMap()) return true;
        Boolean cached = IplStraddleRenderCache.visibility(sub, pose);
        if (cached != null) return cached;
        boolean visible = compute(sub, pose);
        IplStraddleRenderCache.cacheVisibility(sub, pose, visible);
        return visible;
    }

    /**
     * Whether the plot box of {@code sub}, placed by {@code pose} and grown by {@code margin}
     * blocks, can be in view of the pass being drawn: the main view or the portal view IP is
     * rendering. Light-view (shadow) passes are never rejected by the camera frustum.
     */
    public static boolean inFrustum(ClientSubLevel sub, Pose3dc pose, double margin) {
        if (IrisInterface.invoker.isRenderingShadowMap()) return true;
        return inFrustum(worldBounds(sub, pose), margin);
    }

    private static boolean compute(ClientSubLevel sub, Pose3dc pose) {
        double[] bounds = worldBounds(sub, pose);
        // Frustum only: a piece is not a half-space (material beside a frame stays in its
        // piece on either side of the plane), the per-section plans skip what is not drawn.
        return inFrustum(bounds, 0.0);
    }

    /** World AABB {minX, minY, minZ, maxX, maxY, maxZ} of the plot box under {@code pose}. */
    private static double[] worldBounds(ClientSubLevel sub, Pose3dc pose) {
        var plot = sub.getPlot().getBoundingBox();
        double x = (plot.minX() + (double) plot.maxX() + 1) * 0.5;
        double y = (plot.minY() + (double) plot.maxY() + 1) * 0.5;
        double z = (plot.minZ() + (double) plot.maxZ() + 1) * 0.5;
        Vec3 center = pose.transformPosition(new Vec3(x, y, z));
        Vec3 ex = pose.transformPosition(new Vec3(plot.maxX() + 1.0, y, z)).subtract(center);
        Vec3 ey = pose.transformPosition(new Vec3(x, plot.maxY() + 1.0, z)).subtract(center);
        Vec3 ez = pose.transformPosition(new Vec3(x, y, plot.maxZ() + 1.0)).subtract(center);
        double hx = Math.abs(ex.x) + Math.abs(ey.x) + Math.abs(ez.x);
        double hy = Math.abs(ex.y) + Math.abs(ey.y) + Math.abs(ez.y);
        double hz = Math.abs(ex.z) + Math.abs(ey.z) + Math.abs(ez.z);
        return new double[]{center.x - hx, center.y - hy, center.z - hz, center.x + hx, center.y + hy, center.z + hz};
    }

    private static boolean inFrustum(double[] b, double margin) {
        var frustum = ((IEWorldRenderer) Minecraft.getInstance().levelRenderer).portal_getFrustum();
        return frustum == null || frustum.isVisible(new AABB(
            b[0] - margin, b[1] - margin, b[2] - margin,
            b[3] + margin, b[4] + margin, b[5] + margin));
    }
}
