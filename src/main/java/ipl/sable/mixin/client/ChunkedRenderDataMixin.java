package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.render.IplClipDiagnostics;
import ipl.sable.render.IplClipUniformPatcher;
import ipl.sable.render.IplPieceRender;
import ipl.sable.render.IplProgramRegistry;
import ipl.sable.render.IplSubLevelRenderVisibility;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Sable's chunked sub-level draw ({@code renderChunkedSubLevel}) as a draw of one piece.
 *
 * <p>A body without session images draws as before. A straddling body draws its native piece at
 * its own pose and every projection draws its session's piece at the mapped pose; per section
 * {@link IplPieceRender#drawSection} chooses SKIP, FULL, RANGES or SPLIT. Only SPLIT runs enable
 * the sub-level cut ({@code gl_ClipDistance[1]}); IP's portal slot 0 is untouched apart from the
 * plot-local conversion below.
 *
 * <p>IP's slot-0 equation is camera-relative world space, while Sable's chunk vertices are
 * plot-local until the model-view matrix rotates and scales them, so inside a portal pass
 * slot 0 is converted to plot-local space for this draw only and restored on RETURN. Empty
 * layers and hidden sub-levels ({@link IplSubLevelRenderVisibility}) are skipped.
 */
@Pseudo
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class ChunkedRenderDataMixin {

    @Shadow
    public abstract ClientSubLevel getSubLevel();

    @Shadow @Final
    private ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections;

    /** The piece draw of this call, null for an ordinary whole-body draw. */
    @Unique
    private IplPieceRender.Draw ipl$draw;

    /** IP slot 0 was temporarily converted to Sable's plot-local input space. */
    @Unique
    private double[] ipl$portalClipEquationThisCall;

    @Inject(method = "renderChunkedSubLevel", at = @At("HEAD"), cancellable = true)
    private void ipl$beginPieceDraw(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        this.ipl$draw = null;
        this.ipl$portalClipEquationThisCall = null;
        ClientSubLevel sub = getSubLevel();

        // Empty cutout/translucent/etc. layers must not touch clip state or run shader work.
        boolean populated = false;
        for (var section : this.allRenderSections) {
            if (!section.getCompiled().isEmpty(type)) { populated = true; break; }
        }
        if (!populated || !IplSubLevelRenderVisibility.visible(sub, sub.renderPose())) {
            ci.cancel();
            return;
        }

        IplPieceRender.Draw draw = null;
        IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
        if (frame != null) {
            int node = IplPieceRender.drawNode(sub, frame);
            draw = node < 0 ? null : IplPieceRender.begin(frame, node, sub.renderPose());
            if (draw == null) {
                // Nothing of this piece here (or a transit ghost without a native piece).
                ci.cancel();
                return;
            }
        }

        if (FrontClipping.isClippingEnabled
            && IplProgramRegistry.isVanillaSubLevelInputShader(shader.getName())) {
            double[] portalEquation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
            if (IplClipUniformPatcher.patchPortalClipForVanillaSubLevel(shader, sub, portalEquation)) {
                this.ipl$portalClipEquationThisCall = portalEquation;
            }
        }
        IplClipDiagnostics.onVanillaCall(draw != null);
        if (draw == null) return;
        // SPLIT runs enable slots 1/2 for themselves; whatever a previous pass left is off.
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        IplClipUniformPatcher.beginChunkInputSpace();
        this.ipl$draw = draw;
    }

    @WrapOperation(
        method = "renderChunkedSubLevel",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V")
    )
    private void ipl$drawPieceSection(
        VertexBuffer buffer, Operation<Void> original,
        @Local SectionRenderDispatcher.RenderSection section
    ) {
        IplPieceRender.Draw draw = this.ipl$draw;
        if (draw == null) {
            original.call(buffer);
            return;
        }
        IplPieceRender.drawSection(draw, section, buffer, () -> original.call(buffer));
    }

    @Inject(method = "renderChunkedSubLevel", at = @At("RETURN"))
    private void ipl$endPieceDraw(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        if (this.ipl$portalClipEquationThisCall != null) {
            IplClipUniformPatcher.restorePortalClip(shader, this.ipl$portalClipEquationThisCall);
            this.ipl$portalClipEquationThisCall = null;
        }
        if (this.ipl$draw == null) return;
        this.ipl$draw = null;
        // Slots 1 and 2 belong to this bracket only (IP owns slot 0): a stale equation must not
        // leak into programs bound later in the frame.
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        IplClipUniformPatcher.endChunkInputSpace();
        IplClipUniformPatcher.clearAndUpload();
    }
}
