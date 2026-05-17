package ipl.sable.mixin.client;

import ipl.sable.render.SubLevelClipUniformPatcher;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Mirror IP's {@code GL_CLIP_PLANE0} (slot 0) enable/disable state onto our
 * {@code GL_CLIP_DISTANCE1} (slot 1) so the two slots stay in sync across
 * portal-through render transitions.
 *
 * <p><b>Why this exists:</b> {@link IplShaderClipMirrorMixin} hooks
 * {@code RenderSystem.setShader} and enables {@code GL_CLIP_DISTANCE1} when
 * IP's portal-through clipping is active. But if a portal-through render
 * runs and then ends mid-frame (IP calls {@link FrontClipping#disableClipping}),
 * our setShader hook returns early next time {@code FrontClipping.isClippingEnabled}
 * is false -- it doesn't disable our slot. {@code GL_CLIP_DISTANCE1} stays on
 * with the last-set uniform value, and the subsequent main-render block-entity
 * pass evaluates the discard against a stale equation. User reported chests
 * on the airship being invisible in the main source-dim view -- exact symptom
 * of that stale slot-1 state.
 *
 * <p>Hooking IP's own enable/disable methods is the most direct fix: whenever
 * IP toggles its slot 0, we toggle our slot 1 the same way. This guarantees the
 * two stay coherent regardless of portal-through start/end timing relative to
 * other state changes.
 *
 * <p>Doesn't conflict with our per-sub-level bracket in
 * {@link SableSourceClipMixin} -- that mixin's HEAD enable will idempotently
 * re-enable a state that's already on, and its RETURN already checks
 * {@code FrontClipping.isClippingEnabled} before disabling.
 */
@Mixin(value = FrontClipping.class, remap = false)
public abstract class IplFrontClippingStateMirrorMixin {

    /**
     * IP's {@code enableClipping} is private; we target by method name + the
     * inject point at TAIL so we fire AFTER they've flipped {@code isClippingEnabled = true}
     * and called {@code GL11.glEnable(GL_CLIP_PLANE0)}.
     */
    @Inject(
        method = "enableClipping",
        at = @At("TAIL"),
        remap = false,
        require = 0
    )
    private static void ipl$mirrorEnable(CallbackInfo ci) {
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
    }

    /**
     * Hook IP's public {@code disableClipping}. At HEAD we're before they've
     * flipped {@code isClippingEnabled = false} but the conditional check that
     * skips the work if already disabled lives in the same method body -- our
     * inject fires before that check. We disable unconditionally; redundant
     * disables on already-disabled state are a no-op.
     */
    @Inject(
        method = "disableClipping",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private static void ipl$mirrorDisable(CallbackInfo ci) {
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        // Also zero the per-shader uniform so the next render that re-enables
        // slot 1 doesn't start with whatever stale value the last portal-through
        // wrote. clearAndUpload targets the currently bound shader; if none is
        // bound or no shader has the uniform, the call is a safe no-op.
        SubLevelClipUniformPatcher.clearAndUpload();
    }
}
