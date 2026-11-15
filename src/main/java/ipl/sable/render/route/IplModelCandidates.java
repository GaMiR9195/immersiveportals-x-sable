package ipl.sable.render.route;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Block entities whose models may have to be routed, per level. A model is noticed when a draw
 * of it is claimed, when Flywheel gives it up, and by a scan around every opening: the scan finds
 * models that no pass of this frame draws (their block is out of view and only the model is seen
 * through a portal, or their level is only seen through one) and Flywheel visuals, which never
 * reach a block-entity renderer. Bodies move, so their block entities are scanned every frame (a
 * fast body must not wait for a scan); the static world every {@link #SCAN_INTERVAL_TICKS} ticks.
 * Render thread only; weak references.
 */
final class IplModelCandidates {

	private static final int SCAN_INTERVAL_TICKS = 10;
	/** Blocks this far from an opening are scanned (long models reach it from there). */
	private static final double SCAN_REACH = 16.0;
	/**
	 * A body's block entities whose block (at the body's tick pose) is this far from an opening
	 * are offered: the scan reach plus a generous allowance for the body's motion within a tick and
	 * for unbounded renderers. Big ships near a portal then cost only their nearby block entities
	 * per frame instead of all of them (each offer asks the renderer for its bounds).
	 */
	private static final double BODY_SCAN_REACH = 32.0;

	private static final Map<Level, Set<BlockEntity>> BY_LEVEL = new WeakHashMap<>();
	private static final Map<Level, Long> SCANNED = new WeakHashMap<>();

	private IplModelCandidates() {}

	static void add(BlockEntity be) {
		Level level = be.getLevel();
		if (level != null) BY_LEVEL.computeIfAbsent(level, l -> Collections.newSetFromMap(new WeakHashMap<>())).add(be);
	}

	static void forget(BlockEntity be) {
		Set<BlockEntity> set = be.getLevel() == null ? null : BY_LEVEL.get(be.getLevel());
		if (set != null) set.remove(be);
	}

	/** Candidates of {@code level} (live view). */
	static Set<BlockEntity> of(Level level) {
		Set<BlockEntity> set = BY_LEVEL.get(level);
		return set == null ? Set.of() : set;
	}

	/** Candidates of every level (copy). */
	static List<BlockEntity> all() {
		List<BlockEntity> out = new ArrayList<>();
		for (Set<BlockEntity> set : BY_LEVEL.values()) out.addAll(set);
		return out;
	}

	/** Scans the bodies of every level, and the world of levels whose interval has passed; adds what {@code routed} accepts. */
	static void refresh(Predicate<BlockEntity> routed) {
		for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
			List<IplRouteRect> zones = zones(level);
			if (zones.isEmpty()) continue;
			scanBodies(level, zones, routed);
			long time = level.getGameTime();
			Long last = SCANNED.get(level);
			if (last != null && time >= last && time - last < SCAN_INTERVAL_TICKS) continue;
			SCANNED.put(level, time);
			scanWorld(level, zones, routed);
		}
	}

	private static List<IplRouteRect> zones(ClientLevel level) {
		List<IplOpening> openings = IplOpenings.of(level);
		if (openings.isEmpty()) return List.of();
		IplRouteFrame root = IplOpenings.root(level);
		List<IplRouteRect> out = new ArrayList<>(openings.size());
		for (IplOpening o : openings) out.add(root.rectOf(o));
		return out;
	}

	/** Block entities of the level's loaded chunks near an opening (global portals span every chunk). */
	private static void scanWorld(ClientLevel level, List<IplRouteRect> zones, Predicate<BlockEntity> routed) {
		if (!(level.getChunkSource() instanceof ImmPtlClientChunkMap chunks)) return;
		int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
		List<LevelChunk> near = chunks.readChunkMap(map -> {
			List<LevelChunk> out = new ArrayList<>();
			for (LevelChunk chunk : map.values()) {
				int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
				if (nearAny(zones, new AABB(x, minY, z, x + 16, maxY, z + 16), SCAN_REACH)) out.add(chunk);
			}
			return out;
		});
		for (LevelChunk chunk : near) offer(chunk, zones, routed);
	}

	/** Block entities of bodies near an opening. */
	private static void scanBodies(ClientLevel level, List<IplRouteRect> zones, Predicate<BlockEntity> routed) {
		if (!(SubLevelContainer.getContainer(level) instanceof ClientSubLevelContainer container)) return;
		Vector3d at = new Vector3d();
		for (ClientSubLevel body : container.getAllSubLevels()) {
			if (!nearAny(zones, body.boundingBox().toMojang(), SCAN_REACH)) continue;
			Pose3dc pose = body.logicalPose();
			for (PlotChunkHolder holder : body.getPlot().getLoadedChunks()) {
				LevelChunk chunk = holder.getChunk();
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos pos = be.getBlockPos();
					Vector3d w = pose.transformPosition(at.set(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
					AABB box = new AABB(w.x - 0.5, w.y - 0.5, w.z - 0.5, w.x + 0.5, w.y + 0.5, w.z + 0.5);
					if (nearAny(zones, box, BODY_SCAN_REACH) && routed.test(be)) add(be);
				}
			}
		}
	}

	private static void offer(LevelChunk chunk, List<IplRouteRect> zones, Predicate<BlockEntity> routed) {
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (zones != null && !nearAny(zones, new AABB(be.getBlockPos()), SCAN_REACH)) continue;
			if (routed.test(be)) add(be);
		}
	}

	private static boolean nearAny(List<IplRouteRect> zones, AABB box, double reach) {
		for (IplRouteRect zone : zones) if (zone.near(box, reach)) return true;
		return false;
	}
}
