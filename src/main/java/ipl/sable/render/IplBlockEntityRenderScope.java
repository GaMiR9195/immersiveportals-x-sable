package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.ArrayDeque;

/**
 * Keeps a sub-level clip alive until its deferred block-entity vertices are drawn.
 * A block-entity renderer only appends vertices to BufferSource; the shader runs at
 * endBatch(), so a per-render-call bracket ends too early to control visibility.
 */
public final class IplBlockEntityRenderScope {

    private static final ThreadLocal<ArrayDeque<ClientSubLevel>> ACTIVE =
        ThreadLocal.withInitial(ArrayDeque::new);

    private IplBlockEntityRenderScope() {}

    public static boolean isActiveFor(ClientSubLevel sub) {
        return ACTIVE.get().contains(sub);
    }

    public static void renderAndFlush(
        ClientSubLevel sub, MultiBufferSource.BufferSource buffers, Runnable render
    ) {
        // A plain two-block body has no BE vertices: no bracket, GL query, flush,
        // dispatcher matrices or shader upload is needed for its empty BE pass.
        if (!IplSubLevelRenderVisibility.hasBlockEntities(sub)) return;
        IplClipPortalFinder.ClipDecision decision =
            IplClipPortalFinder.findStraddlingPortalPlane(sub);
        if (decision == null || decision.planes().isEmpty()) {
            disableStalePortalClipOutsidePortalPass();
            IplClipUniformPatcher.disableSlots();
            render.run();
            return;
        }

        // Finish prior, unrelated vertices BEFORE changing their clip equations.
        // This boundary is outside the entire sub-level renderer, never inside a BER.
        buffers.endBatch();
        IplClipUniformPatcher.patchForSubLevel(sub, decision.planes());
        // IP owns slot 0 while its portal bracket is live. Outside that bracket it
        // must be off: a portal pass can otherwise leave an old slot-0 equation
        // enabled and cull native destination BEs after the parent handoff.
        disableStalePortalClipOutsidePortalPass();
        ACTIVE.get().addLast(sub);
        try {
            render.run();
            // Flush only after Sable has finished this sub-level's complete BE pass.
            // Flushing inside each renderer corrupts renderers that build shared buffers.
            buffers.endBatch();
        } finally {
            ACTIVE.get().removeLastOccurrence(sub);
            if (ACTIVE.get().isEmpty()) ACTIVE.remove();
            IplClipUniformPatcher.disableSlots();
            IplClipUniformPatcher.clearAndUpload();
        }
    }

    private static void disableStalePortalClipOutsidePortalPass() {
        if (!FrontClipping.isClippingEnabled && !PortalRendering.isRendering()) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
        }
    }
}
