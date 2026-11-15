package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.render.IplClipDiagnostics;
import ipl.sable.render.IplPieceClip;
import ipl.sable.render.IplProgramBindHook;
import ipl.sable.render.IplPieceRender;
import ipl.sable.render.IplSubLevelRenderVisibility;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable's chunked sub-level draw ({@code renderChunkedSubLevel}) as a draw of one piece.
 *
 * <p>A body without session images draws as before. A straddling body draws its native piece at
 * its own pose and every projection draws its session's piece at the mapped pose; per section
 * {@link IplPieceRender#drawSection} chooses SKIP, FULL, RANGES or SPLIT, and only SPLIT runs
 * set a piece cut. The portal clip of the pass applies to every run unchanged: cuts are
 * evaluated in clip space, so Sable's plot-local vertex input needs no conversion. Empty layers
 * and hidden sub-levels ({@link IplSubLevelRenderVisibility}) are skipped.
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

	@Inject(method = "renderChunkedSubLevel", at = @At("HEAD"), cancellable = true)
	private void ipl$beginPieceDraw(
		RenderType type, ShaderInstance shader, Matrix4f modelView,
		double camX, double camY, double camZ,
		CallbackInfo ci
	) {
		this.ipl$draw = null;
		ClientSubLevel sub = getSubLevel();
		boolean populated = false;
		for (var section : this.allRenderSections) {
			if (!section.getCompiled().isEmpty(type)) { populated = true; break; }
		}
		if (!populated || !IplSubLevelRenderVisibility.visible(sub, sub.renderPose())) {
			ci.cancel();
			return;
		}
		// The bound program may have been made current with raw GL, and the piece draws below
		// include raw multi-draws that no draw hook sees.
		IplProgramBindHook.beforeDraw();
		IplClipDiagnostics.onSubLevelDraw(sub);
		IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
		if (frame == null) return;
		int node = IplPieceRender.drawNode(sub, frame);
		IplPieceRender.Draw draw = node < 0 ? null : IplPieceRender.begin(frame, node, sub.renderPose());
		if (draw == null) {
			// Nothing of this piece here (or a transit ghost without a native piece).
			ci.cancel();
			return;
		}
		IplPieceClip.clear();
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
		if (this.ipl$draw == null) return;
		this.ipl$draw = null;
		IplPieceClip.clear();
	}
}
