package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.duck.IplClusterOutlineAnchor;
import ipl.sable.duck.IplSubLevelDuck;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.createmod.catnip.outliner.ChasingAABBOutline;
import net.createmod.catnip.outliner.Outline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.my_util.Plane;

import java.util.function.Consumer;

/** Catnip selections follow the SAME native/image instances as sub-level geometry.
 * Portal slot 0 still clips the view; slots 1/2 split the object, even in root views.
 * The draw must finish INSIDE each pose/clip scope, not in the global Catnip flush.
 */
public final class IplHostedOutlineRenderer {
    private IplHostedOutlineRenderer() {}

    public static void render(Outline outline, SuperRenderTypeBuffer originalBuffer,
                              Consumer<SuperRenderTypeBuffer> draw) {
        Vec3 anchor = null;
        if (outline instanceof IplClusterOutlineAnchor cluster && cluster.ipl$getOutlineAnchor() != null) {
            anchor = Vec3.atCenterOf(cluster.ipl$getOutlineAnchor());
        } else if (outline instanceof ChasingAABBOutline box) {
            anchor = box.getBounds().getCenter();
        }
        ClientSubLevel sub = anchor == null ? null : IplClientHostedLookup.findHostedSubLevel(anchor);
        ClientLevel level = WorldRenderInfo.isRendering()
            ? WorldRenderInfo.getTopRenderInfo().world : Minecraft.getInstance().level;
        if (sub == null || level == null) {
            draw.accept(originalBuffer);
            return;
        }
        var parent = ((IplSubLevelDuck) sub).ipl$getParentLevel();
        if (parent == null) { draw.accept(originalBuffer); return; }
        var cuts = SourceClipPortalFinder.findStraddlingPortalPlanes(sub);
        // Collect images BEFORE setting an override; computing a new image from an
        // already overridden renderPose would accidentally apply the portal twice.
        var images = IplClientHostedLookup.getStraddleProjectionsInto(level);
        boolean nativeHere = parent.dimension().equals(level.dimension());
        if (nativeHere) {
            if (cuts.isEmpty()) draw.accept(originalBuffer);
            else drawClipped(sub, cuts.get(0).plane(), cuts.size() > 1 ? cuts.get(1).plane() : null, draw);
        }
        // Do not choose source OR image by the block centre. A single outlined block
        // can straddle the plane; BOTH draws are needed with complementary cuts.
        for (var image : images) {
            if (image.sub() != sub) continue;
            IplStraddleRenderState.set(sub, image.mappedPose(), image.destPlane(), image.portal());
            try {
                drawClipped(sub, image.destPlane(), null, draw);
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    private static void drawClipped(ClientSubLevel sub, Plane first, Plane second,
                                    Consumer<SuperRenderTypeBuffer> draw) {
        var previous = SubLevelClipUniformPatcher.captureState();
        boolean clip1 = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE1);
        boolean clip2 = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE2);
        try (var buffers = new IplOutlineBuffer()) {
            SubLevelClipUniformPatcher.patchForSubLevel(sub, first, second);
            draw.accept(buffers);
            buffers.draw();
        } finally {
            SubLevelClipUniformPatcher.restoreState(previous);
            if (clip1) GL11.glEnable(GL30.GL_CLIP_DISTANCE1); else GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            if (clip2) GL11.glEnable(GL30.GL_CLIP_DISTANCE2); else GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        }
    }
}
