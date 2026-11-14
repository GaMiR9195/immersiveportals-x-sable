package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplProjectionLevelScope;
import ipl.sable.render.route.IplGeometryRouting;
import ipl.sable.transit.IplPieceMap;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * The block-entity pass of one piece draw, flushed before its cut ends. A block-entity renderer
 * only appends vertices to BufferSource; the shader runs at endBatch(), so a per-render-call
 * bracket ends too early to control visibility.
 *
 * <p>For a straddling body the pass runs once for the block entities wholly in the drawn piece
 * (they are away from every aperture) and once per aperture edge of that piece for the block
 * entities whose model reaches the edge's zone ({@link IplPieceRender#rejectsBlockEntity}). A
 * model that extends past its block (springs, ropes, cogwheels) counts with its render bounds,
 * so both pieces draw it; which of its parts each piece writes is decided by the parts' route
 * through the opening, never by a plane ({@link IplGeometryRouting}). Edge passes of the passed
 * piece also draw the parts of other models that went through that aperture.
 */
public final class IplBlockEntityRenderScope {

	private static final ThreadLocal<ArrayDeque<ClientSubLevel>> ACTIVE =
		ThreadLocal.withInitial(ArrayDeque::new);

	private IplBlockEntityRenderScope() {}

	public static boolean isActiveFor(ClientSubLevel sub) {
		return ACTIVE.get().contains(sub);
	}

	public static void renderAndFlush(
		ClientSubLevel sub, MultiBufferSource.BufferSource buffers, Runnable render
	) {
		IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
		boolean ownModels = IplSubLevelRenderVisibility.hasBlockEntities(sub);
		if (frame == null) {
			if (ownModels) render.run();
			return;
		}
		// A plain body without block entities still draws the foreign geometry passing its apertures.
		if (!ownModels && !IplGeometryRouting.hasImages(sub)) return;
		int node = IplPieceRender.drawNode(sub, frame);
		// Nothing of this piece here (or a transit ghost without a native piece).
		if (node < 0 || !frame.draws(node)) return;
		// Finish prior, unrelated vertices BEFORE the filter and the cut change.
		// This boundary is outside the entire sub-level renderer, never inside a BER.
		buffers.endBatch();
		pass(sub, buffers, render, frame, node, null);
		for (IplPieceMap.Edge e : frame.splitEdges(node)) pass(sub, buffers, render, frame, node, e);
	}

	private static void pass(ClientSubLevel sub, MultiBufferSource.BufferSource buffers, Runnable render,
							 IplPieceRender.Frame frame, int node, @Nullable IplPieceMap.Edge edge) {
		IplPieceClip.clear();
		IplPieceRender.beginBlockEntityFilter(sub, frame, node, edge);
		ACTIVE.get().addLast(sub);
		try {
			render.run();
			if (edge != null) {
				IplProjectionLevelScope.runInOwnerLevel(sub,
					() -> IplGeometryRouting.drawImages(sub, frame, node, edge));
			}
			// Flush only after Sable has finished this sub-level's complete BE pass.
			// Flushing inside each renderer corrupts renderers that build shared buffers.
			buffers.endBatch();
		} finally {
			ACTIVE.get().removeLastOccurrence(sub);
			if (ACTIVE.get().isEmpty()) ACTIVE.remove();
			IplPieceRender.endBlockEntityFilter();
			IplPieceClip.clear();
		}
	}
}
