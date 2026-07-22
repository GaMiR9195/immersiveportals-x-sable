package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.SourceClipDiag;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelClipUniformPatcher;
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
 * Sodium parity for {@link SableSourceClipMixin}: when Sodium is loaded, Sable
 * bypasses {@code SubLevelRenderDispatcher} entirely and renders each sub-level
 * through Sodium's own {@code RenderSectionManager} pipeline. The per-sub-level
 * entry point is {@link SubLevelRenderSectionManager#render}, which is what we
 * hook here. Identical clip-plane logic to the vanilla path: write our
 * independent {@code ipl_subLevelClipEquation} uniform (gl_ClipDistance[1])
 * and enable {@code GL_CLIP_DISTANCE1} for the draw.
 */
// NOTE (sable 2.0 migration): SubLevelRenderSectionManager no longer exists — Sable 2.0
// restructured the Sodium path into mixin impls (sublevel_render/impl/sodium). String
// target keeps this compiling and silently inert; re-target when the Sodium backend work
// happens (it remains documented-untested).
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.sodium.SubLevelRenderSectionManager", remap = false)
public abstract class SableSourceClipSodiumMixin {

    @Shadow(remap = false)
    private ClientSubLevel subLevel;

    @Unique
    private boolean ipl$installedClipThisCall;

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

        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(this.subLevel);
        if (decision == null) {
            SourceClipDiag.onSodiumCall(false);
            return;
        }

        SubLevelClipUniformPatcher.patchForSubLevel(this.subLevel, decision.plane());
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);

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
        // Mirror SableSourceClipMixin: only disable slot 1 if IP isn't using it
        // for portal-through. See that mixin for the full rationale.
        if (!FrontClipping.isClippingEnabled) {
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        }
        SubLevelClipUniformPatcher.clearAndUpload();
    }
}
