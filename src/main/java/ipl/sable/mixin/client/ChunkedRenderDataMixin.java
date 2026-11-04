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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
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
import qouteall.q_misc_util.my_util.Plane;

/**
 * Clipping of Sable's chunked sub-level draw ({@code renderChunkedSubLevel}).
 *
 * <p>The part of a straddling sub-level that went into a portal's aperture must not be drawn
 * in its own level: the projection on the other side draws it. Around each straddling draw
 * this mixin installs the cell of the drawn piece ({@code ipl_subLevelClipEquation[SLOTS]} in
 * {@code gl_ClipDistance[1..SLOTS]}, independent of IP's portal slot 0): an intersection of
 * kept half-spaces {@code normal · p + c > 0} that excludes only the portal prisms, so
 * material beside a portal, beyond its plane, stays drawn. A projection pass goes through the
 * same bracket with its own piece's cell ({@link IplClipPortalFinder}).
 *
 * <p>IP's slot-0 equation is camera-relative world space, while Sable's chunk vertices are
 * plot-local until the model-view matrix rotates and scales them, so inside a portal pass
 * slot 0 is converted to plot-local space for this draw only and restored on RETURN. Empty
 * layers, hidden sub-levels ({@link IplSubLevelRenderVisibility}) and draws whose kept region
 * is empty (the portal cut against any plane of the cell) are skipped.
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
            double[] cut = this.ipl$portalClipEquationThisCall.clone();
            cut[3] -= FrontClipping.ADJUSTMENT;
            Vec3 camera = CHelper.getCurrentCameraPos();
            for (Plane plane : decision.planes()) {
                Vec3 n = plane.normal();
                double[] split = {n.x, n.y, n.z, -n.dot(plane.pos().subtract(camera))};
                if (IplRenderCullMath.emptyIntersection(cut, split)) {
                    IplClipUniformPatcher.restorePortalClip(shader, this.ipl$portalClipEquationThisCall);
                    this.ipl$portalClipEquationThisCall = null;
                    ci.cancel();
                    return;
                }
            }
        }
        if (decision == null || decision.planes().isEmpty()) {
            IplClipDiagnostics.onVanillaCall(false);
            return;
        }
        // Чанковые вершины Sable plot-локальны: только в этой скобке программы
        // rendertype_* получают нормаль в plot-local входе
        // (IplClipUniformPatcher#chunkInputNormal).
        IplClipUniformPatcher.beginChunkInputSpace();
        // Плоскости ячейки куска (IplClipCells) идут в слоты 1..SLOTS, слот 0 остаётся за IP.
        IplClipUniformPatcher.patchForSubLevel(getSubLevel(), decision.planes());
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

        // Slots 1..SLOTS belong to this bracket only (IP owns slot 0), so they are always
        // disabled on exit: the bracket's enable is the sole source of "sub-level cut active"
        // GL state, and a stale equation cannot leak into programs bound later in the frame
        // (e.g. Iris terrain of the destination world inheriting the airship cell).
        IplClipUniformPatcher.disableSlots();
        IplClipUniformPatcher.endChunkInputSpace();
        IplClipUniformPatcher.clearAndUpload();
    }
}
