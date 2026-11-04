package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.render.IplClipDiagnostics;
import ipl.sable.render.IplClipPortalFinder;
import ipl.sable.render.IplClipUniformPatcher;
import ipl.sable.render.IplProgramRegistry;
import ipl.sable.render.IplRenderCullMath;
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
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Clipping of Sable's chunked sub-level draw ({@code renderChunkedSubLevel}).
 *
 * <p>A sub-level that straddles a portal must not be drawn past the portal plane in its own
 * level: that part is drawn by the projection on the other side. Around each straddling draw
 * this mixin installs the sub-level cut ({@code ipl_subLevelClipEquation} in
 * {@code gl_ClipDistance[1]} and {@code [2]}, independent of IP's portal slot 0); the kept
 * half-space faces the source side ({@code normal · p + c > 0}). The projection pass goes
 * through the same bracket with the complementary plane ({@link IplClipPortalFinder}).
 *
 * <p>IP's slot-0 equation is camera-relative world space, while Sable's chunk vertices are
 * plot-local until the model-view matrix rotates and scales them, so inside a portal pass
 * slot 0 is converted to plot-local space for this draw only and restored on RETURN. Empty
 * layers, hidden sub-levels ({@link IplSubLevelRenderVisibility}) and draws whose kept region
 * is empty (portal cut ∩ sub-level cut) are skipped.
 */
@Pseudo
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class ChunkedRenderDataMixin {

    @Shadow
    public abstract ClientSubLevel getSubLevel();

    @Shadow @Final
    private ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections;

    /** The sub-level cut was installed by this call and must be torn down on RETURN. */
    @Unique
    private boolean ipl$installedClipThisCall;

    /** IP slot 0 was temporarily converted to Sable's plot-local input space. */
    @Unique
    private double[] ipl$portalClipEquationThisCall;

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$installClipPlaneIfStraddling(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        this.ipl$installedClipThisCall = false;
        this.ipl$portalClipEquationThisCall = null;

        // Empty cutout/translucent/etc. layers must not install clipping or run shader
        // work at all. A two-block plot usually has geometry in only ONE of these layers.
        boolean populated = false;
        for (var section : this.allRenderSections) {
            if (!section.getCompiled().isEmpty(type)) { populated = true; break; }
        }
        if (!populated || !IplSubLevelRenderVisibility.visible(getSubLevel(), getSubLevel().renderPose())) {
            ci.cancel();
            return;
        }

        IplClipPortalFinder.ClipDecision decision =
            IplClipPortalFinder.findStraddlingPortalPlane(getSubLevel());
        // Sable's vanilla chunk vertices are plot-local until its model-view
        // matrix rotates and scales them. IP's slot-0 equation is camera-relative
        // world space, so using it unchanged cuts a rotated or scaled airship on a
        // wrong plane. Convert only for this scoped Sable draw, then restore it
        // before the parent world's terrain uses the same shader.
        if (FrontClipping.isClippingEnabled
            && IplProgramRegistry.isVanillaSubLevelInputShader(shader.getName())) {
            double[] portalEquation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
            if (IplClipUniformPatcher.patchPortalClipForVanillaSubLevel(
                shader, getSubLevel(), portalEquation
            )) {
                this.ipl$portalClipEquationThisCall = portalEquation;
            }
        }

        if (decision != null && this.ipl$portalClipEquationThisCall != null
            && !IrisInterface.invoker.isRenderingShadowMap()) {
            var n = decision.plane().normal();
            var delta = decision.plane().pos().subtract(CHelper.getCurrentCameraPos());
            double[] split = {n.x, n.y, n.z, -n.dot(delta)};
            double[] cut = this.ipl$portalClipEquationThisCall.clone();
            cut[3] -= FrontClipping.ADJUSTMENT;
            if (IplRenderCullMath.emptyIntersection(cut, split)) {
                IplClipUniformPatcher.restorePortalClip(shader, this.ipl$portalClipEquationThisCall);
                this.ipl$portalClipEquationThisCall = null;
                ci.cancel();
                return;
            }
        }
        if (decision == null) {
            IplClipDiagnostics.onVanillaCall(false);
            return;
        }
        // Чанковые вершины Sable plot-локальны: только в этой скобке программы
        // rendertype_* получают нормаль в plot-local входе
        // (IplClipUniformPatcher#chunkInputNormal).
        IplClipUniformPatcher.beginChunkInputSpace();
        // Вторая плоскость: у родного куска — второй корневой портал, у куска проекции —
        // вход в следующий портал цепочки. Обе идут в слоты 1 и 2, слот 0 остаётся за IP.
        IplClipUniformPatcher.patchForSubLevel(
            getSubLevel(), decision.plane(), IplClipPortalFinder.secondPlaneFor(getSubLevel()));
        this.ipl$installedClipThisCall = true;
        IplClipDiagnostics.onVanillaCall(true);
    }

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("RETURN")
    )
    private void ipl$teardownClipPlane(
        RenderType type, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        if (this.ipl$portalClipEquationThisCall != null) {
            IplClipUniformPatcher.restorePortalClip(shader, this.ipl$portalClipEquationThisCall);
            this.ipl$portalClipEquationThisCall = null;
        }

        if (!this.ipl$installedClipThisCall) return;
        this.ipl$installedClipThisCall = false;

        // Slots 1 and 2 belong to this bracket only (IP owns slot 0), so they are always
        // disabled on exit: the bracket's enable is the sole source of "sub-level cut active"
        // GL state, and a stale equation cannot leak into programs bound later in the frame
        // (e.g. Iris terrain of the destination world inheriting the airship plane).
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        IplClipUniformPatcher.endChunkInputSpace();
        IplClipUniformPatcher.clearAndUpload();
    }
}
