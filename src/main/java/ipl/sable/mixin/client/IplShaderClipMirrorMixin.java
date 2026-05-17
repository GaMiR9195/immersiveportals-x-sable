package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

import java.util.function.Supplier;

/**
 * Mirror IP's currently-installed clip equation onto our independent
 * {@code ipl_subLevelClipEquation} uniform (gl_ClipDistance[1]) on every
 * {@link RenderSystem#setShader} so that ALL geometry rendered through
 * affected shaders gets clipped during portal-through render -- not just
 * Sable sub-level chunks.
 *
 * <p><b>Why this exists:</b> IP's {@code MixinRenderSystem_Clipping}
 * unconditionally calls {@code FrontClipping.unsetClippingUniform()} on
 * every {@code setShader} call when Iris is present, presumably assuming
 * shaderpack-side portal handling will take over. But shaderpacks only
 * handle terrain via their own gl_ClipDistance writes; entities (block
 * entities like chests, mob renderers, item entities) are left unclipped
 * during the portal-through view. The user's previous test showed a chest
 * in nether visible from the back of a source portal -- IP's slot-0
 * uniform was being zeroed, so IP's discard write evaluated to no-clip.
 *
 * <p>This mixin restores the clip behavior by writing IP's installed
 * equation to our own slot-1 uniform on every shader bind, and enabling
 * {@code GL_CLIP_DISTANCE1} whenever IP's portal-through clipping is
 * active ({@link FrontClipping#isClippingEnabled}). Entity / block-entity
 * shaders that we extended in {@code shader_transformation.yaml} now
 * write {@code gl_ClipDistance[1]} from this uniform, so the discard
 * fires regardless of what IP did with slot 0.
 *
 * <p><b>Coordination with sub-level clip:</b>
 * {@link SableSourceClipMixin} writes a per-sub-level equation into the
 * same uniform during {@code renderChunkedSubLevel}. That mixin's HEAD
 * inject runs AFTER this {@code setShader} hook fires (since the shader
 * is bound before Sable's render call), so the per-sub-level equation
 * naturally overrides this mirror. After Sable's RETURN, the next
 * {@code setShader} call refreshes this hook again, restoring IP's
 * equation for the rest of the render. So:
 *
 * <ul>
 *   <li>Outside any sub-level draw, this hook drives slot 1 from IP's
 *       portal-through state.</li>
 *   <li>Inside a sub-level draw, the SableSourceClip path takes over.</li>
 *   <li>After RETURN, this hook reasserts on the next shader switch.</li>
 * </ul>
 *
 * <p>Priority left default so this fires after IP's
 * {@code MixinRenderSystem_Clipping} (which is what we're augmenting).
 */
@Mixin(value = RenderSystem.class, remap = false)
public class IplShaderClipMirrorMixin {

    @Inject(
        method = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V",
        at = @At("RETURN")
    )
    private static void ipl$mirrorClipEquationToSlot1(
        Supplier<ShaderInstance> supplier,
        CallbackInfo ci
    ) {
        if (!FrontClipping.isClippingEnabled) {
            // IP isn't asking for clipping right now. Don't write our uniform here
            // -- leaving it at whatever the last setter (zero from clearAndUpload,
            // or our per-sub-level equation from the patcher) put there. The
            // gl_ClipDistance[1] write in our shaders will still happen on every
            // vertex; if GL_CLIP_DISTANCE1 is enabled, it'll use whatever value
            // is currently on the GPU.
            return;
        }

        double[] worldEq = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        if (worldEq == null) return;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;

        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        if (uniform == null) return;

        uniform.set(
            (float) worldEq[0],
            (float) worldEq[1],
            (float) worldEq[2],
            (float) worldEq[3]
        );
        uniform.upload();

        // Mirror IP's GL_CLIP_PLANE0 enable: ensure GL_CLIP_DISTANCE1 is also on
        // so the hardware respects our gl_ClipDistance[1] writes. We avoid
        // disabling here since SableSourceClipMixin manages its own enable for
        // per-sub-level clipping during the main render where IP's slot 0 isn't
        // active.
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
    }
}
