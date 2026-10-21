package ipl.sable.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Compatibility facade for late shader binds. Never reuse another portal layer's
 * last plane: recursive rendering may return from B to A before either draws.
 */
public final class IplClipEquationCache {
    private IplClipEquationCache() {}

    /** Kept for existing callers; no global plane is retained any more. */
    public static void refreshFromActive() {}

    public static double[] getWorldEq() {
        if (!PortalRendering.isRendering()) return null;
        var plane = PortalRendering.getActiveClippingPlane();
        if (plane == null) return null;
        var n = plane.normal();
        var relative = plane.pos().subtract(CHelper.getCurrentCameraPos());
        return new double[]{n.x, n.y, n.z, -n.dot(relative)};
    }

    public static double[] getEyeEq() {
        double[] world = getWorldEq();
        if (world == null) return null;
        Vector4f eye = new Vector4f((float) world[0], (float) world[1],
            (float) world[2], (float) world[3]);
        new Matrix4f(RenderSystem.getModelViewMatrix()).invert().transpose().transform(eye);
        return new double[]{eye.x, eye.y, eye.z, eye.w};
    }
}
