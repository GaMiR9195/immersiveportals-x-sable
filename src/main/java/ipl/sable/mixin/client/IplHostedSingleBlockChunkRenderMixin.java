package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import ipl.sable.client.IplHostedRenderRouting;
import ipl.sable.dim.IplDimAgnostic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hosted sub-levels always render through Sable's CHUNKED path, never the one-block path.
 *
 * <h2>The bug this removes</h2>
 *
 * <p>A one-block hosted body carried into a portal showed a crooked portal cut: a
 * triangular slice on the far side of the portal whose size and even visibility changed
 * as the player moved the camera. Multi-block bodies were unaffected.
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
 * <p>All of this mod's portal clipping is attached to the chunked path only --
 * {@code SableSourceClipMixin} targets {@code renderChunkedSubLevel}, and
 * {@code SubLevelClipUniformPatcher.patchPortalClipForVanillaSubLevel} converts IP's
 * slot-0 {@code iportal_ClippingEquation} into exactly that path's plot-local input
 * space. Nothing in this mod referenced {@code VanillaSingleSubLevelRenderData} at all.
 *
 * <p>So for a one-block body, IP's slot-0 equation -- built in camera-relative WORLD
 * space -- was dotted against an EYE-space vertex position. Eye space is
 * {@code R_view * p_world}, and {@code n_world dot (R_view * p) == (R_view^T * n_world) dot p},
 * i.e. the effective cut normal was the portal normal rotated by the inverse camera
 * rotation. The plane therefore swung around as the player turned: a plane at a wrong
 * oblique angle slicing a single cube is precisely a triangular corner cut, and its
 * area changed every time the camera moved. Our own slot-1/slot-2 sub-level cut was
 * never installed for that path either, so the source/destination split it is supposed
 * to make was simply missing.
 *
 * <h2>This is NOT a 0.5.1 regression</h2>
 *
 * <p>Worth recording, because it was assumed to be one: {@code SableSourceClipMixin}
 * already existed in 0.5.0 and already clipped {@code renderChunkedSubLevel} only, so the
 * eye-space mismatch was present the whole time. What 0.5.1 changed is that a crossing body
 * now keeps drawing its destination half mid-transit, which is what made the pre-existing,
 * camera-dependent cut actually visible. Reverting the interpolation work would hide the
 * symptom, not fix the cause -- so this mixin stays, and it is required, not optional.
 *
 * <h2>The fix</h2>
 *
 * <p>Rather than duplicating the whole clip bracket into a second, batched,
 * differently-spaced draw path (and having to split Sable's shared one-block mesh per
 * sub-level so a non-straddling block in the same batch is not cut too), hosted bodies
 * are routed to the chunked path that already clips correctly.
 *
 * <p>This is a first-class, supported Sable configuration, not a hack:
 * {@code isSingleBlock} already returns false for any block tagged
 * {@code SableTags.ALWAYS_CHUNK_RENDERING}, so chunk-rendering a 1x1x1 plot is a path
 * Sable maintains deliberately. Returning false here is the same decision, keyed on
 * "is this body ours" instead of on a block tag.
 *
 * <p>The decision is also STABLE for the body's whole lifetime -- hosting never changes
 * while a sub-level exists -- so it cannot thrash
 * {@code resize()}'s {@code instanceof ... ^ isSingleBlock(...)} re-allocation, which a
 * straddle-dependent choice would have done on every crossing (once per tick in a
 * portal loop).
 *
 * <p>Bonus: the one-block path is also the path that historically lost visibility
 * entirely, because it depends on the separate {@code renderAfterSections} drain and on
 * {@code singleBlockLayers} being non-empty. Hosted bodies no longer depend on it.
 *
 * <p>{@code @Pseudo} because Sable is a runtime dependency, {@code remap = false}
 * because Sable's names are not in the Mojang mappings. Since {@code require = 0} makes a
 * missed target silent, and a missed target here means the eye-space cut returns with no
 * error, the handler reports that it ran to {@link IplHostedRenderRouting}, which warns once
 * from the client heartbeat if a hosted body exists and the hook never fired.
 * {@code -Dipl.sable.forceChunkedForHosted=false} restores Sable's own routing.
 */
@Pseudo
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class IplHostedSingleBlockChunkRenderMixin {

    @Inject(
        method = "isSingleBlock",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void ipl$forceChunkedRenderingForHosted(
        ClientSubLevel subLevel, CallbackInfoReturnable<Boolean> cir
    ) {
        IplHostedRenderRouting.markRoutingHookFired();
        if (subLevel == null || !IplHostedRenderRouting.forceChunkedForHosted()) return;
        try {
            if (IplDimAgnostic.isHosted(subLevel)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // A transient plot/level read during allocation must never break rendering;
            // falling through leaves Sable's own decision untouched.
        }
    }
}
