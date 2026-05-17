package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.render.SourceClipDiag;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Phase 3a: source-side render clipping for Sable sub-levels straddling a portal.
 *
 * <p>When an airship is mid-crossing a portal, its world-space geometry extends past
 * the portal frame on the destination side. Without intervention the source-dim
 * render draws the whole airship -- the player sees a complete airship that appears
 * to clip through the portal frame instead of cleanly straddling it. The mirror in
 * the dest dim (rendered through the portal-through view) covers the dest side, but
 * the source side over-renders.
 *
 * <p>This mixin installs IP's {@link FrontClipping} clip plane around Sable's
 * {@code renderChunkedSubLevel} so geometry past the portal plane is discarded.
 * The plane's normal points to the source (camera) side: points where
 * {@code normal · p + c > 0} are kept. IP's
 * {@code MixinShaderInstance + ShaderCodeTransformation} already adds the
 * {@code iportal_ClippingEquation} uniform to the {@code rendertype_solid /
 * rendertype_cutout} shaders Sable uses for chunked rendering, so the clip equation
 * we install propagates into the actual fragment discard.
 *
 * <p><b>Guarded against IP's own use:</b> if IP is in the middle of rendering a
 * portal-through view ({@link PortalRendering#isRendering()}), IP has already
 * configured its own inner clipping for the cross-dim view (and that's what gives
 * the mirror its dest-side clip for free). We don't touch FrontClipping in that
 * path -- the existing state has to survive until IP itself tears it down.
 *
 * <p><b>Mirror side:</b> mirrors are kinematic sub-levels in the dest dim. When the
 * player is in the source dim looking through the portal at the mirror, IP's
 * portal-through pass already clips the mirror at the portal plane (because that's
 * what IP's clipping does for the whole dest-dim view). So this mixin only needs to
 * handle the source side; mirror clipping is free. If the player walks <i>into</i>
 * the dest dim and looks at the mirror from there, the mirror will straddle a
 * portal too (in that dim) and the same logic will pick the right plane.
 *
 * <p>{@code @Pseudo} because Sable's class is a runtime dep, not compile-time.
 * {@code remap = false} because Sable's identifiers aren't part of the MC mappings.
 */
@Pseudo
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class SableSourceClipMixin {

    @Shadow(remap = false)
    public abstract ClientSubLevel getSubLevel();

    /**
     * Was FrontClipping's clip state enabled when we entered? If yes, IP is using it
     * for something (the entity render path enables clipping briefly even outside
     * portal-through render in some compat paths); we leave well enough alone. If
     * no, we owned the slot, so we tear our config down on RETURN.
     */
    @Unique
    private boolean ipl$installedClipThisCall;

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void ipl$installClipPlaneIfStraddling(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        this.ipl$installedClipThisCall = false;

        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(getSubLevel());
        if (decision == null) {
            SourceClipDiag.onVanillaCall(false);
            return;
        }

        // Independent clip plane: write to our ipl_subLevelClipEquation uniform
        // (gl_ClipDistance[1]), not IP's slot 0. That way our per-sub-level clip
        // doesn't conflict with IP's own pipeline in scenarios where both want to
        // be active (notably the portal-through render, where IP wants to clip
        // the dest-dim view at the portal frame AND we additionally want to clip
        // the mirror's near-portal half). Both planes evaluate per-vertex; a
        // fragment is culled if either says so.
        SubLevelClipUniformPatcher.patchForSubLevel(getSubLevel(), decision.plane());

        // Enable hardware respect for gl_ClipDistance[1] writes. GL_CLIP_DISTANCE1
        // is 0x3001 in modern GL; some IDEs / compat shims expose it as
        // GL30.GL_CLIP_DISTANCE1 or GL11.GL_CLIP_PLANE1 (same enum value).
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);

        this.ipl$installedClipThisCall = true;
        SourceClipDiag.onVanillaCall(true);
    }

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("RETURN"),
        remap = false,
        require = 0
    )
    private void ipl$teardownClipPlane(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        if (!this.ipl$installedClipThisCall) return;
        this.ipl$installedClipThisCall = false;

        // If IP's portal-through clipping is active, leave GL_CLIP_DISTANCE1
        // enabled -- IplShaderClipMirrorMixin is responsible for keeping slot 1
        // populated from IP's equation during that whole pass, and the next
        // shader switch will refresh our uniform from IP's state. Disabling here
        // would kill clipping for the rest of the portal-through render.
        //
        // Otherwise (main render of source dim, no portal-through), we owned the
        // GL_CLIP_DISTANCE1 enable and need to clear it so subsequent non-Sable
        // draws aren't clipped by stale state.
        if (!FrontClipping.isClippingEnabled) {
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        }
        SubLevelClipUniformPatcher.clearAndUpload();
    }
}
