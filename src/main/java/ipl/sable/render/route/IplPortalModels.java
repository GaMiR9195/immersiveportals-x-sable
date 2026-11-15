package ipl.sable.render.route;

import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleSessionStore;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.render.IplFlywheelFallback;
import ipl.sable.render.IplSubLevelRenderVisibility;
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
 * <p>A model is <em>routed</em> when its bounds — swept over its body's motion during the tick,
 * so speed never lets it slip past — come near an opening of its frame ({@link IplOpenings}:
 * every flat teleporting portal, global portals of dimension stacking included). A routed model
 * is recorded once per render frame at its real pose ({@link IplModelCapture}); its RECORDED
 * geometry, not the bounds its renderer reports, decides what is cut and which frame every part
 * is in ({@link IplRouteSolver}, {@link IplRoutedModel}). A model that does not reach across stays
 * whole; it is drawn by the same path, so nothing switches between two ways of drawing when it
 * starts or stops crossing. Every level pass of the frame — the main view and each
 * portal view, nested passes included — then writes exactly the parts whose frame is in that
 * level, placed through the portals: what went in is drawn beyond the exit and nowhere else,
 * what did not stays, and a part beside a finite opening never moves. A Flywheel visual handed
 * over ahead of that is drawn by its renderer here, uncut ({@link IplDirectModel}).
 *
 * <p>The normal draws of a routed model are claimed ({@link #claim}): the dispatcher funnel
 * and the piece passes of straddling bodies skip it, and the pass writes it at its block-entity
 * stage ({@link #drawPass}) or at the claim, whichever comes later. Render thread only.
 */
public final class IplPortalModels {

	/**
	 * A model is routed while its swept bounds are this close to an opening, plus its motion per
	 * tick: it is recorded before any of it can reach across, at any speed.
	 */
	private static final double ROUTE_MARGIN = 1.0;
	/**
	 * Flywheel hands a visual over this far ahead, plus twice its motion per tick: the hand-over
	 * applies in Flywheel's next frame, so its renderer has it before it is routed.
	 */
	private static final double FLYWHEEL_LEAD = 2.0;
	/**
	 * An unbounded renderer (tracks, arcs, beams) is recorded while an opening is this close to
	 * its block; its recorded geometry then decides exactly.
	 */
	private static final double UNBOUNDED_REACH = 32.0;
	/**
	 * A routed model stays routed while the geometry it actually drew last frame is this close to
	 * an opening, plus its motion per tick. Its renderer's reported bounds alone may be stale,
	 * cached or too small (many mods report the block, or a box refreshed every few ticks); a model
	 * whose real geometry still reaches the opening must never fall back to a whole draw in one
	 * frame, which puts every part on the holder's side of the portal for that frame.
	 */
	private static final double RELEASE_MARGIN = 2.0;
	/** Frames a model whose renderer failed to record is drawn as it is before routing is retried. */
	private static final long RETRY_FRAMES = 600L;

	/** How close a model is to the openings of its frame. */
	private enum Reach { NONE, NEAR, ROUTED }

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
	private static final Map<BlockEntity, Reach> REACH = new IdentityHashMap<>();
	private static final Map<BlockEntity, IplPortalModel> MODELS = new IdentityHashMap<>();
	/** What each routed model keeps between frames: recorder and touch graph ({@link IplRouteMemory}). */
	private static final Map<BlockEntity, IplRouteMemory> MEMORY = new WeakHashMap<>();
	/** Models whose renderer failed to record: drawn as they are until the given frame. */
	private static final Map<BlockEntity, Long> UNROUTABLE = new WeakHashMap<>();
	/** The anchor of each block entity asked about this frame (one per frame: reach and recording agree). */
	private static final Map<BlockEntity, Object> ANCHORS = new IdentityHashMap<>();
	private static final Object NO_ANCHOR = new Object();
	private static long frame = Long.MIN_VALUE;

	private IplPortalModels() {}

	// ------------------------------------------------------------------ passes

	/** HEAD of a level render ({@code LevelRenderer.renderLevel}). */
	public static void beginPass(@Nullable ClientLevel level, Vec3 camera) {
		if (level == null) return;
		sync();
		if (PASSES.isEmpty()) {
			IplPortalModelDiagnostics.frame();
			IplModelCandidates.refresh(IplPortalModels::isNearOpening);
		}
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
	 * The block-entity stage of the current level pass: writes the parts of every model drawn
	 * here that are in this level (the level's own models and images coming through portals).
	 */
	public static void drawPass(MultiBufferSource buffers) {
		Pass pass = PASSES.peekLast();
		if (pass == null || IrisInterface.invoker.isRenderingShadowMap()) return;
		pass.stageDone = true;
		for (BlockEntity be : IplModelCandidates.all()) {
			if (isDrawnHere(be)) write(pass, be, buffers);
			else if (!isNearOpening(be)) IplModelCandidates.forget(be);
		}
	}

	// ------------------------------------------------------------------ draws

	/** The block-entity draw funnel: a crossing model is claimed, anything else is drawn by {@code draw}. */
	public static void render(BlockEntity be, MultiBufferSource buffers, Consumer<MultiBufferSource> draw) {
		if (!claim(be, buffers)) draw.accept(buffers);
	}

	/**
	 * Claims a normal draw of {@code be} when its model crosses an opening: the caller must not
	 * draw it. Outside level passes (shadow maps, GUI) nothing is claimed. {@code buffers}: where
	 * the draw would have gone, or null for the pass's buffers.
	 */
	public static boolean claim(BlockEntity be, @Nullable MultiBufferSource buffers) {
		if (IplModelCapture.isCapturing()) return false;
		Pass pass = PASSES.peekLast();
		if (pass == null || IrisInterface.invoker.isRenderingShadowMap() || !isRouted(be)) return false;
		IplModelCandidates.add(be);
		if (pass.stageDone) write(pass, be, buffers != null ? buffers : Minecraft.getInstance().renderBuffers().bufferSource());
		return true;
	}

	/** Lets the next stages see a model that no draw claims (Flywheel visuals handed over). */
	public static void notice(BlockEntity be) {
		IplModelCandidates.add(be);
	}

	/** Known block entities of {@code level} near an opening (copy). */
	public static List<BlockEntity> candidates(Level level) {
		return new ArrayList<>(IplModelCandidates.of(level));
	}

	private static void write(Pass pass, BlockEntity be, MultiBufferSource buffers) {
		if (!pass.written.add(be)) return;
		IplPortalModel model = model(be);
		if (!model.drawsIn(pass.level)) return;
		AABB bounds = model.boundsIn(pass.level);
		if (bounds != null && !IplSubLevelRenderVisibility.inPassFrustum(bounds)) {
			IplPortalModelDiagnostics.culled();
			return;
		}
		long start = IplPortalModelDiagnostics.start();
		model.emit(pass.level, buffers, pass.camera);
		IplPortalModelDiagnostics.written(start);
	}

	// ------------------------------------------------------------------ reach

	/** Whether the model of {@code be} is routed this frame (recorded, split by its geometry, claimed). */
	public static boolean isRouted(BlockEntity be) {
		return reach(be) == Reach.ROUTED;
	}

	/** Whether the model of {@code be} is near an opening this frame (Flywheel hands it over ahead). */
	public static boolean isNearOpening(BlockEntity be) {
		return reach(be) != Reach.NONE;
	}

	private static Reach reach(BlockEntity be) {
		sync();
		Reach known = REACH.get(be);
		if (known != null) return known;
		Reach reach = unroutable(be) ? Reach.NONE : computeReach(be);
		REACH.put(be, reach);
		return reach;
	}

	private static Reach computeReach(BlockEntity be) {
		Level level = be.getLevel();
		if (!(level instanceof ClientLevel)) return Reach.NONE;
		if (IplOpenings.of(level).isEmpty() && IplStraddleSessionStore.isIdle()) return Reach.NONE;
		IplModelAnchor anchor = anchor(be);
		// A hidden transit successor draws nothing (its ghost does).
		if (anchor == null || anchor.owner() != null && IplParentDimSync.isSuppressedSuccessor(anchor.owner())) {
			return Reach.NONE;
		}
		Reach reach = reachOf(be, anchor);
		IplPortalModelDiagnostics.reach(be, reach.name(), anchor.frame().depth);
		return reach;
	}

	private static Reach reachOf(BlockEntity be, IplModelAnchor anchor) {
		// Part of the model is drawn beyond an opening: it stays routed wherever it is now.
		IplRouteMemory memory = MEMORY.get(be);
		boolean known = memory != null && memory.level == anchor.level();
		if (known && memory.holding) return Reach.ROUTED;
		IplModelAnchor.ModelBounds bounds = anchor.modelBounds(be);
		// What it really drew last frame (route space of the same level): release hysteresis.
		AABB drawn = known ? memory.geometry : null;
		double release = RELEASE_MARGIN + (bounds == null ? 0.0 : bounds.lead());
		Vector3d p = anchor.point();
		Reach reach = Reach.NONE;
		for (IplRouteFrame f = anchor.frame(); f != null; f = f.parent) {
			for (IplOpening opening : IplOpenings.of(f.level)) {
				IplRouteRect rect = f.rectOf(opening);
				if (drawn != null && rect.near(drawn, release)) return Reach.ROUTED;
				if (bounds == null) {
					if (rect.near(new AABB(p.x, p.y, p.z, p.x, p.y, p.z), UNBOUNDED_REACH)) return Reach.ROUTED;
					continue;
				}
				for (AABB box : bounds.boxes()) {
					if (rect.near(box, ROUTE_MARGIN + bounds.lead())) return Reach.ROUTED;
					if (rect.near(box, FLYWHEEL_LEAD + 2.0 * bounds.lead())) reach = Reach.NEAR;
				}
			}
		}
		return reach;
	}

	// ------------------------------------------------------------------ models

	/** Routed models, and Flywheel visuals of the world handed to their renderers, are drawn here. */
	private static boolean isDrawnHere(BlockEntity be) {
		if (be.isRemoved()) return false;
		return isRouted(be) || Flywheel.LOADED && IplFlywheelFallback.isHandedOver(be);
	}

	private static IplPortalModel model(BlockEntity be) {
		sync();
		IplPortalModel model = MODELS.get(be);
		if (model == null) {
			model = build(be);
			MODELS.put(be, model);
		}
		return model;
	}

	private static IplPortalModel build(BlockEntity be) {
		IplModelAnchor anchor = anchor(be);
		if (anchor == null) return IplRoutedModel.EMPTY;
		boolean drawFlywheelParts = Flywheel.LOADED && !IplFlywheelFallback.flywheelDraws(be);
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		if (!isRouted(be)) {
			IplPortalModelDiagnostics.direct();
			return new IplDirectModel(be, anchor, partialTick, drawFlywheelParts);
		}
		long start = IplPortalModelDiagnostics.start();
		IplRouteMemory memory = MEMORY.computeIfAbsent(be, ignored -> new IplRouteMemory());
		memory.frame = frame;
		IplModelRecorder recorder = IplModelCapture.record(be, anchor, partialTick, drawFlywheelParts, memory.recorder);
		IplPortalModelDiagnostics.recorded(start);
		if (recorder == null) {
			// Its draw is claimed already: draw it as it is this frame rather than not at all.
			UNROUTABLE.put(be, frame + RETRY_FRAMES);
			MEMORY.remove(be);
			return new IplDirectModel(be, anchor, partialTick, drawFlywheelParts);
		}
		IplRoutedModel routed = IplRoutedModel.route(recorder, anchor, memory);
		if (IplPortalModelDiagnostics.ENABLED) IplPortalModelDiagnostics.route(be, routed.summary());
		return routed;
	}

	/** The anchor of {@code be} this frame (null: removed, not in a client level). */
	@Nullable
	private static IplModelAnchor anchor(BlockEntity be) {
		sync();
		Object known = ANCHORS.get(be);
		if (known == null) {
			IplModelAnchor made = IplModelAnchor.of(be);
			ANCHORS.put(be, made == null ? NO_ANCHOR : made);
			return made;
		}
		return known == NO_ANCHOR ? null : (IplModelAnchor) known;
	}

	private static boolean unroutable(BlockEntity be) {
		Long until = UNROUTABLE.get(be);
		if (until == null) return false;
		if (frame < until) return true;
		UNROUTABLE.remove(be);
		return false;
	}

	private static void sync() {
		long current = IplStraddleRenderCache.frameId();
		if (current == frame) return;
		frame = current;
		REACH.clear();
		MODELS.clear();
		ANCHORS.clear();
		// A session that holds nothing ends once its model is no longer routed.
		if (!MEMORY.isEmpty()) {
			MEMORY.entrySet().removeIf(e -> e.getKey().isRemoved() || !e.getValue().holding && e.getValue().frame < current - 1);
		}
	}
}
