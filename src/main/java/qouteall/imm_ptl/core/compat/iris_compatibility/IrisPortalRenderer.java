package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.NameTagFootprints;
import qouteall.imm_ptl.core.render.PortalFogOverlay;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_INCR;
import static org.lwjgl.opengl.GL11.GL_KEEP;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glStencilFunc;
import static org.lwjgl.opengl.GL11.glStencilOp;

/**
 * Shader-pack portal rendering with one composite buffer per portal layer.
 *
 * <p>Every view is a complete Iris frame. After a view, its image is copied into the buffer of
 * its layer where the stencil marks it; portals found in it are drawn there in turn, each with
 * the view's fog laid over it ({@link PortalFogOverlay}). Pixels that must stay in front of portal
 * content are kept out of every layer ({@link IrisForegroundMask}). Each view keeps its
 * composite as the texture of the portal surfaces it draws next frame ({@link IrisPortalBackdrop}).
 */
public class IrisPortalRenderer extends PortalRenderer {
    public static final IrisPortalRenderer instance = new IrisPortalRenderer();

    /** Pulls the view area onto the portal surface the view drew, which TAA jitters by up to half a pixel. */
    private static final float VIEW_AREA_OFFSET_FACTOR = -1.0f;
    private static final float VIEW_AREA_OFFSET_UNITS = -4.0f;

    /** What a layer's view needs again while its portals are composited. */
    private record LayerView(
        Matrix4f modelView, Matrix4f projection, List<NameTagFootprints.Footprint> nameTags,
        PortalFogOverlay.Fog fog, IrisPortalBackdrop backdrop
    ) {}

    private SecondaryFrameBuffer[] deferredFbs = new SecondaryFrameBuffer[0];
    private LayerView[] layerViews = new LayerView[0];

    private boolean portalRenderingNeeded = false;
    private boolean nextFramePortalRenderingNeeded = false;

    IrisPortalRenderer() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PreGameRenderEvent.class, preGameRenderEvent -> {
            updateNeedsPortalRendering();
        });
    }

    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }

    @Override
    public void prepareRendering() {
        Validate.isTrue(!PortalRendering.isRendering());

        IPCGlobal.useSeparatedStencilFormat = !IPMcHelper.isNvidiaVideocard();

        if (deferredFbs.length != PortalRendering.getMaxPortalLayer() + 1) {
            for (SecondaryFrameBuffer fb : deferredFbs) {
                if (fb.fb != null) fb.fb.destroyBuffers();
            }

            deferredFbs = new SecondaryFrameBuffer[PortalRendering.getMaxPortalLayer() + 1];
            for (int i = 0; i < deferredFbs.length; i++) {
                deferredFbs[i] = new SecondaryFrameBuffer();
            }
            layerViews = new LayerView[deferredFbs.length];
        }

        if (portalRenderingNeeded) {
            for (SecondaryFrameBuffer deferredFb : deferredFbs) {
                deferredFb.prepare();
                IPPortingLibCompat.setIsStencilEnabled(deferredFb.fb, true);
            }
            // A layer's color, depth and stencil are all rewritten before they are read, except the
            // marks of kept pixels in layer 0.
            RenderTarget base = deferredFbs[0].fb;
            base.bindWrite(false);
            GlStateManager._clearStencil(0);
            GL11.glStencilMask(0xFF);
            GL11.glClear(GL_STENCIL_BUFFER_BIT);
            base.unbindWrite();
            CHelper.checkGlError();
        }

        IPPortingLibCompat.setIsStencilEnabled(client.getMainRenderTarget(), false);

        client.getMainRenderTarget().bindWrite(false);
    }

    @Override
    public void renderPortalSurface(Portal portal, MultiBufferSource bufferSource) {
        IrisPortalSurface.renderSurface(portal, bufferSource);
    }

    private void updateNeedsPortalRendering() {
        portalRenderingNeeded = nextFramePortalRenderingNeeded;
        nextFramePortalRenderingNeeded = false;
    }

    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
        doMainRenderings(modelView);
    }

    private void doMainRenderings(Matrix4f modelView) {
        CHelper.checkGlError();

        RenderTarget mcFrameBuffer = client.getMainRenderTarget();
        int portalLayer = PortalRendering.getPortalLayer();

        if (portalRenderingNeeded) {
            CHelper.doCheckGlError();

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mcFrameBuffer.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, deferredFbs[portalLayer].fb.frameBufferId);
            GL30.glBlitFramebuffer(
                0, 0, mcFrameBuffer.viewWidth, mcFrameBuffer.viewHeight,
                0, 0, mcFrameBuffer.viewWidth, mcFrameBuffer.viewHeight,
                GL_DEPTH_BUFFER_BIT, GL_NEAREST
            );

            int errorCode = GL11.glGetError();
            if (errorCode != GL_NO_ERROR) {
                IPGlobal.renderMode = IPGlobal.RenderMode.compatibility;
                CHelper.printChat("[Immersive Portals]" +
                    "Switched to compatibility portal rendering mode." +
                    " Portal-in-portal wont' be rendered");
            }

            LayerView view = new LayerView(
                new Matrix4f(modelView), new Matrix4f(RenderSystem.getProjectionMatrix()),
                NameTagFootprints.snapshot(), PortalFogOverlay.shaderPackViewFog(),
                IrisPortalBackdrop.of(IrisViewContexts.current())
            );
            layerViews[portalLayer] = view;
            view.backdrop().evictUnused();

            initStencilForLayer(portalLayer);
            if (portalLayer == 0) {
                IrisForegroundMask.markHand(mcFrameBuffer, deferredFbs[0].fb);
                markNameTags(0);
            }

            // The whole image, not only the view's area: it is also the texture of this view's
            // portal surface in the enclosing view (IrisPortalBackdrop).
            deferredFbs[portalLayer].fb.bindWrite(true);
            MyRenderHelper.drawScreenFrameBuffer(mcFrameBuffer, false, true);
            deferredFbs[portalLayer].fb.unbindWrite();

            mcFrameBuffer.bindWrite(false);
        }

        renderPortals(modelView);

        if (portalLayer == 0) {
            finish();
        }

        mcFrameBuffer.bindWrite(true);
    }

    @Override
    public void onHandRenderingEnded() {

    }

    /**
     * Resets the layer's stencil to its view's area before a portal of the view is drawn.
     * Kept pixels keep their mark: layer 0 is cleared around it, deeper layers are copied from
     * their parent and their own name tags marked again.
     */
    private void initStencilForLayer(int portalLayer) {
        if (portalLayer == 0) {
            deferredFbs[portalLayer].fb.bindWrite(true);
            GL11.glStencilMask(IrisForegroundMask.LAYER_MASK);
            GlStateManager._clearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            GL11.glStencilMask(0xFF);
        }
        else {
            CHelper.checkGlError();

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, deferredFbs[portalLayer - 1].fb.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, deferredFbs[portalLayer].fb.frameBufferId);

            GL30.glBlitFramebuffer(
                0, 0, deferredFbs[0].fb.viewWidth, deferredFbs[0].fb.viewHeight,
                0, 0, deferredFbs[0].fb.viewWidth, deferredFbs[0].fb.viewHeight,
                GL_STENCIL_BUFFER_BIT, GL_NEAREST
            );

            CHelper.checkGlError();

            markNameTags(portalLayer);
        }
    }

    private void markNameTags(int portalLayer) {
        LayerView view = layerViews[portalLayer];
        IrisForegroundMask.markNameTags(
            deferredFbs[portalLayer].fb, portalLayer, view.nameTags(), view.modelView(), view.projection()
        );
    }

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {

    }

    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {

    }

    @Override
    public void finishRendering() {

    }

    private void finish() {
        GlStateManager._colorMask(true, true, true, true);

        if (RenderStates.getRenderedPortalNum() == 0) {
            return;
        }

        if (!portalRenderingNeeded) {
            return;
        }

        RenderTarget mainFrameBuffer = client.getMainRenderTarget();
        mainFrameBuffer.bindWrite(true);

        deferredFbs[0].fb.blitToScreen(mainFrameBuffer.viewWidth, mainFrameBuffer.viewHeight);

        CHelper.checkGlError();
    }

    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        nextFramePortalRenderingNeeded = true;

        if (!portalRenderingNeeded) {
            return;
        }

        if (!tryRenderViewAreaInDeferredBufferAndIncreaseStencil(portal, modelView)) {
            return;
        }

        PortalRendering.pushPortalLayer(portal);

        client.getMainRenderTarget().bindWrite(true);

        renderPortalContent(portal);

        int innerLayer = PortalRendering.getPortalLayer();

        PortalRendering.popPortalLayer();

        int outerLayer = PortalRendering.getPortalLayer();

        if (innerLayer > PortalRendering.getMaxPortalLayer()) {
            return;
        }

        RenderTarget outer = deferredFbs[outerLayer].fb;
        outer.bindWrite(true);
        limitToStencil(innerLayer);
        // Opaque: the content replaces the view's pixels instead of tinting them.
        MyRenderHelper.drawScreenFrameBuffer(deferredFbs[innerLayer].fb, false, false);

        LayerView view = layerViews[outerLayer];
        outer.bindWrite(false);
        limitToStencil(innerLayer);
        PortalFogOverlay.draw(portal, view.fog(), view.modelView(), view.projection(), false);
        glDisable(GL_STENCIL_TEST);
        view.backdrop().store(portal, deferredFbs[innerLayer].fb);

        outer.unbindWrite();
    }

    /** Limits the following draws to the pixels whose stencil holds {@code value}. */
    private static void limitToStencil(int value) {
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, value, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    }

    private boolean tryRenderViewAreaInDeferredBufferAndIncreaseStencil(
        Portal portal, Matrix4f modelView
    ) {

        int portalLayer = PortalRendering.getPortalLayer();

        initStencilForLayer(portalLayer);

        deferredFbs[portalLayer].fb.bindWrite(true);

        GL11.glEnable(GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, portalLayer, 0xFF);
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);

        GlStateManager._enableDepthTest();
        GlStateManager._enablePolygonOffset();
        GlStateManager._polygonOffset(VIEW_AREA_OFFSET_FACTOR, VIEW_AREA_OFFSET_UNITS);

        boolean result = PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                RenderSystem.getProjectionMatrix(),
                true, true, true, true
            );
        });

        GlStateManager._polygonOffset(0, 0);
        GlStateManager._disablePolygonOffset();
        GL11.glDisable(GL_STENCIL_TEST);

        return result;
    }

    @Override
    public void invokeWorldRendering(
        WorldRenderInfo worldRenderInfo
    ) {
        MyGameRenderer.renderWorldNew(
            worldRenderInfo,
            Runnable::run
        );
    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {

    }

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
