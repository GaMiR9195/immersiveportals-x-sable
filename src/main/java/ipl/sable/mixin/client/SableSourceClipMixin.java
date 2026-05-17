package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.render.SourceClipDiag;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

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

        // Don't trample IP's clipping if it's already in use (portal-through render,
        // or any other IP-driven clipped pass).
        if (PortalRendering.isRendering()) {
            SourceClipDiag.onVanillaCall(false);
            return;
        }
        if (FrontClipping.isClippingEnabled) {
            SourceClipDiag.onVanillaCall(false);
            return;
        }

        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(getSubLevel());
        if (decision == null) {
            SourceClipDiag.onVanillaCall(false);
            return;
        }

        // Install. setupInnerClipping computes the equation in camera-relative space
        // and enables GL_CLIP_PLANE0; updateClippingEquationUniformForCurrentShader
        // pushes the world-space equation into the shader uniform.
        FrontClipping.setupInnerClipping(decision.plane(), modelView, 0);
        FrontClipping.updateClippingEquationUniformForCurrentShader(false);

        // Then overwrite the uniform with the per-sub-level transformed equation.
        // Sable's MODEL_VIEW_MATRIX is a pure rotation by the sub-level's
        // orientation; Position + ChunkOffset is in inverse-rotated plot space.
        // The shader's discard runs against that pre-rotation position, so the
        // world-space equation IP installed wouldn't apply -- this rewrite makes
        // it apply correctly per sub-level.
        SubLevelClipUniformPatcher.patchForSubLevel(getSubLevel());

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

        // Disable GL state + zero out the shader uniform so subsequent draws (other
        // sub-levels, the rest of the level, entities) aren't accidentally clipped.
        FrontClipping.disableClipping();
        FrontClipping.unsetClippingUniform();
    }
}
