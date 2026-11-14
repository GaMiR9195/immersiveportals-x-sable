package ipl.sable.render.route;

import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.render.IplFlywheelFallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Block-entity models that pass portals: ropes, springs, chains and connectors between bodies,
 * long shafts, any renderer's model — the same rule for every renderer, Flywheel visuals
 * included (they are handed to their renderers near an opening, {@link IplFlywheelFallback}).
 *
 * <p>A model is <em>routed</em> when its bounds come near an opening of its frame
 * ({@link IplOpenings}: every flat teleporting portal, global portals of dimension stacking
 * included). A routed model is recorded once per render frame at its real pose
 * ({@link IplModelCapture}), cut where it passes openings and split into the frames its paths
 * lead to ({@link IplRoutedModel}). Every level pass of the frame — the main view and each
 * portal view, nested passes included — then writes exactly the parts whose frame is in that
 * level, placed through the portals: what went in is drawn beyond the exit and nowhere else,
 * what did not stays, and a part beside a finite opening never moves.
 *
 * <p>The normal draws of a routed model are claimed ({@link #claim}): the dispatcher funnel and
 * the piece passes of straddling bodies skip it, and the pass writes it at its block-entity
 * stage ({@link #drawPass}) or at the claim, whichever comes later. Render thread only.
 */
public final class IplPortalModels {

	/** A model is routed while its bounds are this close to an opening (covers Flywheel's one-frame handover). */
	private static final double REACH_MARGIN = 1.5;
	/**
	 * An unbounded renderer (tracks, arcs, beams) is routed while an opening is this close to its
	 * block; its recorded geometry then decides exactly.
	 */
	private static final double UNBOUNDED_REACH = 32.0;

	/** Flywheel is optional; its classes are touched only when it is present. */
	private static final class Flywheel {
		static final boolean LOADED = ModList.get().isLoaded("flywheel");
	}

	/** One level render: what it has written already. */
	private static final class Pass {
		final Level level;
		final Vec3 camera;
		final Set<BlockEntity> written = Collections.newSetFromMap(new IdentityHashMap<>());
		/** The block-entity stage ran: claims from now on write at once. */
		boolean stageDone;

		Pass(Level level, Vec3 camera) {
			this.level = level;
			this.camera = camera;
		}
	}

	private static final ArrayDeque<Pass> PASSES = new ArrayDeque<>();
	private static final Map<BlockEntity, Boolean> ROUTED = new IdentityHashMap<>();
	private static final Map<BlockEntity, IplRoutedModel> MODELS = new IdentityHashMap<>();
	/** Models whose renderer failed to record: drawn as they are from then on. */
	private static final Set<BlockEntity> UNROUTABLE = Collections.newSetFromMap(new WeakHashMap<>());
	private static long frame = Long.MIN_VALUE;

	private IplPortalModels() {}

	// ------------------------------------------------------------------ passes

	/** HEAD of a level render ({@code LevelRenderer.renderLevel}). */
	public static void beginPass(@Nullable ClientLevel level, Vec3 camera) {
		if (level == null) return;
		sync();
		if (PASSES.isEmpty()) IplModelCandidates.refresh(IplPortalModels::isRouted);
		PASSES.addLast(new Pass(level, camera));
	}

	/** RETURN of a level render. */
	public static void endPass(@Nullable ClientLevel level) {
		if (level == null) return;
		for (Iterator<Pass> it = PASSES.descendingIterator(); it.hasNext(); ) {
			if (it.next().level == level) {
				it.remove();
				return;
			}
		}
	}

	/**
	 * The block-entity stage of the current level pass: writes the parts of every routed model
	 * that are in this level (the level's own models and images coming through portals).
	 */
	public static void drawPass(MultiBufferSource buffers) {
		Pass pass = PASSES.peekLast();
		if (pass == null || IrisInterface.invoker.isRenderingShadowMap()) return;
		pass.stageDone = true;
		for (BlockEntity be : IplModelCandidates.all()) {
			if (isDrawnRouted(be)) write(pass, be, buffers);
			else IplModelCandidates.forget(be);
		}
	}

	// ------------------------------------------------------------------ draws

	/** The block-entity draw funnel: a routed model is claimed, anything else is drawn by {@code draw}. */
	public static void render(BlockEntity be, MultiBufferSource buffers, Consumer<MultiBufferSource> draw) {
		if (!claim(be, buffers)) draw.accept(buffers);
	}

	/**
	 * Claims a normal draw of {@code be} when its model is routed: the caller must not draw it.
	 * Outside level passes (shadow maps, GUI) nothing is claimed. {@code buffers}: where the
	 * draw would have gone, or null for the pass's buffers.
	 */
	public static boolean claim(BlockEntity be, @Nullable MultiBufferSource buffers) {
		if (IplModelCapture.isCapturing()) return false;
		Pass pass = PASSES.peekLast();
		if (pass == null || IrisInterface.invoker.isRenderingShadowMap() || !isRouted(be)) return false;
		IplModelCandidates.add(be);
		if (pass.stageDone) write(pass, be, buffers != null ? buffers : Minecraft.getInstance().renderBuffers().bufferSource());
		return true;
	}

	/** Known block entities of {@code level} whose models may pass an opening (copy). */
	public static List<BlockEntity> candidates(Level level) {
		return new ArrayList<>(IplModelCandidates.of(level));
	}

	/** Lets the next stages see a model that no draw claims (Flywheel visuals handed over). */
	public static void notice(BlockEntity be) {
		IplModelCandidates.add(be);
	}

	private static void write(Pass pass, BlockEntity be, MultiBufferSource buffers) {
		if (!pass.written.add(be)) return;
		model(be).emit(pass.level, buffers, pass.camera);
	}

	// ------------------------------------------------------------------ routing

	/** Whether the model of {@code be} is routed through openings this frame. */
	public static boolean isRouted(BlockEntity be) {
		sync();
		Boolean known = ROUTED.get(be);
		if (known != null) return known;
		boolean routed = !UNROUTABLE.contains(be) && computeRouted(be);
		ROUTED.put(be, routed);
		return routed;
	}

	private static boolean computeRouted(BlockEntity be) {
		Level level = be.getLevel();
		if (!(level instanceof ClientLevel)) return false;
		if (IplOpenings.of(level).isEmpty() && IplStraddleSessionStore.isIdle()) return false;
		IplModelAnchor anchor = IplModelAnchor.of(be);
		if (anchor == null) return false;
		List<AABB> boxes = anchor.modelBoxes(be);
		Vector3d p = anchor.point();
		AABB block = new AABB(p.x, p.y, p.z, p.x, p.y, p.z);
		for (IplRouteFrame f = anchor.frame(); f != null; f = f.parent) {
			for (IplOpening opening : IplOpenings.of(f.level)) {
				IplRouteRect rect = f.rectOf(opening);
				if (boxes == null ? rect.near(block, UNBOUNDED_REACH) : nearAny(rect, boxes)) return true;
			}
		}
		return false;
	}

	private static boolean nearAny(IplRouteRect rect, List<AABB> boxes) {
		for (AABB box : boxes) if (rect.near(box, REACH_MARGIN)) return true;
		return false;
	}

	/** Routed models are drawn here; so are Flywheel visuals on their way back to Flywheel. */
	private static boolean isDrawnRouted(BlockEntity be) {
		if (be.isRemoved()) return false;
		return isRouted(be) || Flywheel.LOADED && IplFlywheelFallback.isReturning(be);
	}

	private static IplRoutedModel model(BlockEntity be) {
		sync();
		IplRoutedModel model = MODELS.get(be);
		if (model == null) {
			model = record(be);
			MODELS.put(be, model);
		}
		return model;
	}

	private static IplRoutedModel record(BlockEntity be) {
		IplModelAnchor anchor = IplModelAnchor.of(be);
		if (anchor == null) return IplRoutedModel.EMPTY;
		boolean drawFlywheelParts = Flywheel.LOADED && !IplFlywheelFallback.isVisualized(be);
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		IplModelRecorder recorder = IplModelCapture.capture(be, anchor, partialTick, drawFlywheelParts);
		if (recorder == null) {
			UNROUTABLE.add(be);
			return IplRoutedModel.EMPTY;
		}
		return IplRoutedModel.route(recorder, anchor);
	}

	private static void sync() {
		long current = IplStraddleRenderCache.frameId();
		if (current == frame) return;
		frame = current;
		ROUTED.clear();
		MODELS.clear();
	}
}
