package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.render.IplSubLevelRenderVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create: Pocket Sized. Its frustum filter for sub-level draws tests the box that is actually
 * drawn against the frustum of the pass being drawn.
 *
 * <p>Pocket drops a sub-level from Sable's section-layer and block-entity draws when
 * {@code RenderFrame.mayBeVisible} rejects it. That test takes the sub-level's own world box
 * ({@code boundingBox()}) and a single frustum captured at {@code AFTER_SKY}. Under IP neither
 * fits. A projection is drawn by its portal-mapped pose in another dimension, so it vanished
 * whenever its source box was out of the destination camera's view: a ship coming out of a
 * portal was invisible from some distances and angles. And every portal pass captures its own
 * frustum at {@code AFTER_SKY}, so after a portal was drawn the rest of the enclosing view was
 * culled by the portal's frustum. The render pose is the pose of the draw (the mapped pose for
 * a projection), and IP's frustum is the current pass's.
 */
@Pseudo
@Mixin(targets = "com.misterblusky9.pym.api.client.RenderFrame", remap = false)
public abstract class PocketRenderFrameMixin {

    /** Pocket's {@code RenderFrame.PADDING}. */
    @Unique
    private static final double IPL_PADDING = 2.0;

    @Inject(method = "mayBeVisible", at = @At("HEAD"), cancellable = true)
    private static void ipl$testDrawnBoxInCurrentPass(
        SubLevel subLevel, CallbackInfoReturnable<Boolean> cir
    ) {
        if (subLevel instanceof ClientSubLevel client && !client.isRemoved()) {
            cir.setReturnValue(
                IplSubLevelRenderVisibility.inFrustum(client, client.renderPose(), IPL_PADDING));
        }
    }
}
