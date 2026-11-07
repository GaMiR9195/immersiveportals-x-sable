package ipl.sable.render;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.transit.IplPieceMap;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayDeque;

/**
 * The block-entity pass of one piece draw, flushed before its clip state ends. A block-entity
 * renderer only appends vertices to BufferSource; the shader runs at endBatch(), so a
 * per-render-call bracket ends too early to control visibility.
 *
 * <p>For a straddling body the pass runs once for the block entities wholly in the drawn piece
 * (no sub-level cut) and once per aperture edge of that piece for the block entities in the
 * edge's zone, cut by the aperture plane ({@link IplPieceRender#rejectsBlockEntity}).
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
        // A plain two-block body has no BE vertices: no flush, filter or shader upload is needed.
        if (!IplSubLevelRenderVisibility.hasBlockEntities(sub)) return;
        IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
        if (frame == null) {
            disableStalePortalClipOutsidePortalPass();
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
            render.run();
            return;
        }
        int node = IplPieceRender.drawNode(sub, frame);
        // Nothing of this piece here (or a transit ghost without a native piece).
        if (node < 0 || !frame.draws(node)) return;
        // Finish prior, unrelated vertices BEFORE the filter and the clip state change.
        // This boundary is outside the entire sub-level renderer, never inside a BER.
        buffers.endBatch();
        // IP owns slot 0 while its portal bracket is live. Outside that bracket it must be off:
        // a portal pass can otherwise leave an old slot-0 equation enabled.
        disableStalePortalClipOutsidePortalPass();
        Pose3dc drawPose = sub.renderPose();
        pass(sub, buffers, render, frame, node, null, null);
        for (IplPieceMap.Edge e : frame.splitEdges(node)) {
            pass(sub, buffers, render, frame, node, e, IplPieceRender.keepPlane(e, node, drawPose));
        }
    }

    private static void pass(ClientSubLevel sub, MultiBufferSource.BufferSource buffers, Runnable render,
                             IplPieceRender.Frame frame, int node,
                             @Nullable IplPieceMap.Edge edge, @Nullable Plane plane) {
        if (plane != null) {
            IplClipUniformPatcher.patchForSubLevel(sub, plane, null);
        } else {
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        }
        IplPieceRender.beginBlockEntityFilter(sub, frame, node, edge);
        ACTIVE.get().addLast(sub);
        try {
            render.run();
            // Flush only after Sable has finished this sub-level's complete BE pass.
            // Flushing inside each renderer corrupts renderers that build shared buffers.
            buffers.endBatch();
        } finally {
            ACTIVE.get().removeLastOccurrence(sub);
            if (ACTIVE.get().isEmpty()) ACTIVE.remove();
            IplPieceRender.endBlockEntityFilter();
            if (plane != null) {
                GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
                GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
                IplClipUniformPatcher.clearAndUpload();
            }
        }
    }

    private static void disableStalePortalClipOutsidePortalPass() {
        if (!FrontClipping.isClippingEnabled && !PortalRendering.isRendering()) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
        }
    }
}
