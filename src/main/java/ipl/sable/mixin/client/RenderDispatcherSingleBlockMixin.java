package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sub-levels always render through Sable's CHUNKED path, never the one-block path.
 *
 * <p>Sable has two vanilla render paths and they are NOT interchangeable:
 *
 * <ul>
 *   <li>{@code VanillaChunkedSubLevelRenderData.renderChunkedSubLevel} uploads
 *       {@code Position + ChunkOffset} in <b>plot-local, camera-relative</b> space and puts
 *       the sub-level rotation into {@code MODEL_VIEW_MATRIX} afterwards.</li>
 *   <li>{@code VanillaSingleSubLevelRenderData.renderSingleBlock} bakes
 *       {@code modelView * transform} into the vertices on the CPU
 *       ({@code stack.last().pose().mul(modelView).mul(transform)} then
 *       {@code tesselateBlock}), so its {@code Position} attribute is already in
 *       <b>eye</b> space, and every one-block sub-level is batched into ONE
 *       {@code Tesselator} mesh that is drained later from
 *       {@code VanillaSubLevelRenderDispatcher.renderAfterSections}.</li>
 * </ul>
 *
 * <p>All portal clipping of this mod is attached to the chunked path:
 * {@code ChunkedRenderDataMixin} targets {@code renderChunkedSubLevel}, and
 * {@code IplClipUniformPatcher.patchPortalClipForVanillaSubLevel} converts IP's slot-0
 * {@code iportal_ClippingEquation} into exactly that path's plot-local input space. On the
 * one-block path IP's camera-relative WORLD-space equation would be dotted against an
 * EYE-space position ({@code n_world dot (R_view * p) == (R_view^T * n_world) dot p}): the
 * cut normal turns with the camera, a one-block body in a portal shows a triangular corner
 * cut that changes as the player looks around, and the slot-1/slot-2 sub-level cut is
 * missing entirely.
 *
 * <p>Routing every body to the chunked path is a configuration Sable supports itself:
 * {@code isSingleBlock} already returns false for blocks tagged
 * {@code SableTags.ALWAYS_CHUNK_RENDERING}. The answer is the same for every body and stable
 * for its whole lifetime, so it never thrashes {@code resize()}'s
 * {@code instanceof ... ^ isSingleBlock(...)} re-allocation (a straddle-dependent choice would
 * re-allocate on every crossing), and no body depends on the one-block path's separate
 * {@code renderAfterSections} drain.
 */
@Pseudo
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class RenderDispatcherSingleBlockMixin {

    @Inject(
        method = "isSingleBlock",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void ipl$forceChunkedRendering(
        ClientSubLevel subLevel, CallbackInfoReturnable<Boolean> cir
    ) {
        cir.setReturnValue(false);
    }
}
