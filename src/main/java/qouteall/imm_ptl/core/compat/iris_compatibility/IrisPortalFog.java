package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.PortalFogOverlay;

/**
 * {@link PortalFogOverlay} for shader packs. Portals are composited after the level render,
 * which clears the fog when it ends, so each view's fog is kept from inside its level render.
 */
public final class IrisPortalFog {
    private static PortalFogOverlay.Fog lastViewFog = PortalFogOverlay.Fog.NONE;

    private IrisPortalFog() {}

    /** Called inside every level render once its terrain fog is set up. */
    public static void onTerrainFogSetUp() {
        if (!IrisInterface.invoker.isShaders()) return;
        lastViewFog = PortalFogOverlay.current();
    }

    /** The fog of the view whose level render just finished. */
    static PortalFogOverlay.Fog capture() {
        return lastViewFog;
    }

    /** Blends the fog over the portal's pixels of {@code target}, where the stencil holds {@code stencilValue}. */
    static void apply(
        Portal portal, PortalFogOverlay.Fog fog, Matrix4f modelView, Matrix4f projection,
        RenderTarget target, int stencilValue
    ) {
        target.bindWrite(false);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, stencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        PortalFogOverlay.draw(portal, fog, modelView, projection, false);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }
}
