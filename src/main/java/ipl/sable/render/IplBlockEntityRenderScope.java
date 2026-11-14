package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.route.IplPortalModels;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.ArrayDeque;

/**
 * The block-entity pass of one piece draw of a straddling body, flushed after the whole pass. A
 * block-entity renderer only appends vertices to BufferSource; the shader runs at endBatch(),
 * so a per-render-call bracket ends too early to control visibility.
 *
 * <p>The pass draws the block entities of the drawn piece ({@link IplPieceRender#rejectsBlockEntity});
 * models that pass an opening are claimed by {@link IplPortalModels}, which draws each of their
 * parts in its own frame. No plane cuts a block entity.
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
		if (!IplSubLevelRenderVisibility.hasBlockEntities(sub)) return;
		IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
		if (frame == null) {
			render.run();
			return;
		}
		int node = IplPieceRender.drawNode(sub, frame);
		// Nothing of this piece here (or a transit ghost without a native piece).
		if (node < 0 || !frame.draws(node)) return;
		// Finish prior, unrelated vertices BEFORE the filter changes.
		// This boundary is outside the entire sub-level renderer, never inside a BER.
		buffers.endBatch();
		IplPieceRender.beginBlockEntityFilter(sub, frame, node);
		ACTIVE.get().addLast(sub);
		try {
			render.run();
			// Flush only after Sable has finished this sub-level's complete BE pass.
			// Flushing inside each renderer corrupts renderers that build shared buffers.
			buffers.endBatch();
		} finally {
			ACTIVE.get().removeLastOccurrence(sub);
			if (ACTIVE.get().isEmpty()) ACTIVE.remove();
			IplPieceRender.endBlockEntityFilter();
		}
	}
}
