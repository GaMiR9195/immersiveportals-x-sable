package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.sodium.SubLevelRenderSectionManager;
import ipl.sable.render.SourceClipDiag;
import ipl.sable.render.SourceClipPortalFinder;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Sodium parity for {@link SableSourceClipMixin}: when Sodium is loaded, Sable
 * bypasses {@code SubLevelRenderDispatcher} entirely and renders each sub-level
 * through Sodium's own {@code RenderSectionManager} pipeline. The per-sub-level
 * entry point is {@link SubLevelRenderSectionManager#render}, which is what we
 * hook here.
 *
 * <p><b>How the Sodium path is discovered:</b> Sable's
 * {@code SodiumWorldRendererMixin.sable$drawRenderSources} iterates a
 * {@code Map<ClientSubLevel, RenderSectionManager>} when the active dispatcher is
 * the no-op {@code SodiumSubLevelRenderDispatcher} (i.e., the placeholder used to
 * disable the standard SubLevelRenderDispatcher path while Sodium is in play). For
 * each entry it casts the manager to Sable's specialised
 * {@link SubLevelRenderSectionManager} and calls {@code .apply(...)} then
 * {@code .render(...)}. We mixin the render method so we set up / tear down the
 * clip plane once per sub-level draw.
 *
 * <p><b>Clip-plane mechanism:</b> identical to the vanilla path -- IP's
 * {@code MixinSodiumDefaultShaderInterface + MixinIrisSodiumShader} add the
 * {@code iportal_ClippingEquation} uniform to Sodium's chunk shaders, so the
 * same {@code FrontClipping.setupInnerClipping +
 * updateClippingEquationUniformForCurrentShader} flow propagates correctly.
 *
 * <p><b>No matrix arg:</b> Sodium's draw call doesn't take a model-view matrix
 * here (Sodium owns its own matrix state internally). We pass {@code null} to
 * {@link FrontClipping#setupInnerClipping(qouteall.q_misc_util.my_util.Plane, org.joml.Matrix4f, double)}
 * to indicate "use identity for the equation transform", since the camera-relative
 * equation IP builds is already in world space and Sodium's vertex transform
 * lands it back in clip space the same as vanilla.
 *
 * <p>(If null model-view turns out to be wrong with Sodium's shader path, we'll
 * need to thread the active model-view through from Sable's
 * sable$drawRenderSources, but try the simple version first.)
 */
@Pseudo
@Mixin(value = SubLevelRenderSectionManager.class, remap = false)
public abstract class SableSourceClipSodiumMixin {

    @Shadow(remap = false)
    private ClientSubLevel subLevel;

    @Unique
    private boolean ipl$installedClipThisCall;

    // Identity matrix reused for FrontClipping's modelView arg (it transforms an
    // unused "after modelView" equation; the actual shader uniform uses the
    // "before modelView" world-space equation, so the matrix value doesn't matter
    // for our pipeline). A static identity avoids per-frame allocation.
    @Unique
    private static final Matrix4f IPL$IDENTITY = new Matrix4f();

    /**
     * Use the descriptor form so we don't have to import Sodium's
     * {@code ChunkRenderMatrices} (Sodium isn't always on the compile classpath).
     * Mixin matches by name + descriptor.
     */
    @Inject(
        method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/minecraft/client/renderer/RenderType;DDD)V",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void ipl$installClipPlaneIfStraddling(CallbackInfo ci) {
        this.ipl$installedClipThisCall = false;

        // (See SableSourceClipMixin for why we no longer skip on
        // PortalRendering.isRendering -- the mirror needs our patcher's
        // upload step to fire so IP's clip equation actually reaches the GPU
        // before Sable's draw under the portal-through render path.)
        if (FrontClipping.isClippingEnabled) {
            SourceClipDiag.onSodiumCall(false);
            return;
        }

        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(this.subLevel);
        if (decision == null) {
            SourceClipDiag.onSodiumCall(false);
            return;
        }

        FrontClipping.setupInnerClipping(decision.plane(), IPL$IDENTITY, 0);
        FrontClipping.updateClippingEquationUniformForCurrentShader(false);
        this.ipl$installedClipThisCall = true;
        SourceClipDiag.onSodiumCall(true);
    }

    @Inject(
        method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/minecraft/client/renderer/RenderType;DDD)V",
        at = @At("RETURN"),
        remap = false,
        require = 0
    )
    private void ipl$teardownClipPlane(CallbackInfo ci) {
        if (!this.ipl$installedClipThisCall) return;
        this.ipl$installedClipThisCall = false;
        FrontClipping.disableClipping();
        FrontClipping.unsetClippingUniform();
    }
}
