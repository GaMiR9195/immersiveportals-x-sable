package ipl.sable.render;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

/** Sable's vanilla/reach-around updateCulling is a TODO; reject actual instances here. */
public final class IplSubLevelRenderVisibility {
    private IplSubLevelRenderVisibility() {}

    public static boolean hasBlockEntities(ClientSubLevel sub) {
        var data=sub.getRenderData();
        if (data instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData chunks) {
            for (var section:chunks.allRenderSections())
                if (!section.getCompiled().getRenderableBlockEntities().isEmpty()) return true;
            return false;
        }
        if (data instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData single)
            return single.getRenderBlockEntity()!=null;
        return true; // A custom backend can have its own BE storage.
    }

    public static boolean visible(ClientSubLevel sub, Pose3dc pose) {
        // A moved body's successor stays hidden until its transit ghost hands over.
        if (ipl.sable.client.IplParentDimSync.isSuppressedSuccessor(sub)) return false;
        // The main-view frustum is not the light-view frustum. Shadow rendering can
        // reuse this pass and pose; never read/write its camera visibility cache.
        if (qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker.isRenderingShadowMap())
            return true;
        Boolean cached=IplStraddleRenderCache.visibility(sub,pose);
        if (cached!=null) return cached;
        boolean visible=compute(sub,pose);
        IplStraddleRenderCache.cacheVisibility(sub,pose,visible);
        return visible;
    }

    private static boolean compute(ClientSubLevel sub, Pose3dc pose) {
        var plot=sub.getPlot().getBoundingBox();
        if (plot==null) return true;
        double x=(plot.minX()+(double)plot.maxX()+1)*.5;
        double y=(plot.minY()+(double)plot.maxY()+1)*.5;
        double z=(plot.minZ()+(double)plot.maxZ()+1)*.5;
        Vec3 center=pose.transformPosition(new Vec3(x,y,z));
        Vec3 ex=pose.transformPosition(new Vec3(plot.maxX()+1.0,y,z)).subtract(center);
        Vec3 ey=pose.transformPosition(new Vec3(x,plot.maxY()+1.0,z)).subtract(center);
        Vec3 ez=pose.transformPosition(new Vec3(x,y,plot.maxZ()+1.0)).subtract(center);
        double[] bounds=IplRenderCullMath.bounds(v(center),new double[][]{v(ex),v(ey),v(ez)});
        Minecraft mc=Minecraft.getInstance();
        if (mc.levelRenderer!=null) {
            var frustum=((IEWorldRenderer)mc.levelRenderer).portal_getFrustum();
            if (frustum!=null && !frustum.isVisible(new AABB(bounds[0],bounds[1],bounds[2],bounds[3],bounds[4],bounds[5])))
                return false;
        }
        // Both the source and the mapped instance have their OWN pose and kept half.
        var plane=ipl.sable.client.IplStraddleRenderState.getPlaneFor(sub);
        if (plane!=null) return !outside(bounds,plane.normal(),plane.pos());
        for (var decision:SourceClipPortalFinder.findStraddlingPortalPlanes(sub))
            if (outside(bounds,decision.plane().normal(),decision.plane().pos())) return false;
        return true;
    }
    private static boolean outside(double[] b,Vec3 n,Vec3 p) {
        return IplRenderCullMath.outside(b,n.x,n.y,n.z,-n.dot(p));
    }
    private static double[] v(Vec3 v) { return new double[]{v.x,v.y,v.z}; }
}
