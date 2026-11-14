package ipl.sable.render.route;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
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
 * of it is claimed, when Flywheel gives it up, and by a scan around every opening that runs
 * every {@link #SCAN_INTERVAL_TICKS} ticks per level: the scan finds models that no pass of
 * this frame draws (their level is only seen through a portal, or not at all) and Flywheel
 * visuals, which never reach a block-entity renderer. Render thread only; weak references.
 */
final class IplModelCandidates {

	private static final int SCAN_INTERVAL_TICKS = 10;
	/** Blocks this far from an opening are scanned (long models reach it from there). */
	private static final double SCAN_REACH = 16.0;

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

	/** Scans the levels whose interval has passed and adds what {@code routed} accepts. */
	static void refresh(Predicate<BlockEntity> routed) {
		for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
			long time = level.getGameTime();
			Long last = SCANNED.get(level);
			if (last != null && time >= last && time - last < SCAN_INTERVAL_TICKS) continue;
			SCANNED.put(level, time);
			List<IplRouteRect> zones = zones(level);
			if (zones.isEmpty()) continue;
			scanWorld(level, zones, routed);
			scanBodies(level, zones, routed);
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
				if (nearAny(zones, new AABB(x, minY, z, x + 16, maxY, z + 16))) out.add(chunk);
			}
			return out;
		});
		for (LevelChunk chunk : near) offer(chunk, zones, routed);
	}

	/** Block entities of bodies near an opening. */
	private static void scanBodies(ClientLevel level, List<IplRouteRect> zones, Predicate<BlockEntity> routed) {
		if (!(SubLevelContainer.getContainer(level) instanceof ClientSubLevelContainer container)) return;
		for (ClientSubLevel body : container.getAllSubLevels()) {
			if (!nearAny(zones, body.boundingBox().toMojang())) continue;
			for (PlotChunkHolder holder : body.getPlot().getLoadedChunks()) {
				LevelChunk chunk = holder.getChunk();
				if (chunk != null) offer(chunk, null, routed);
			}
		}
	}

	private static void offer(LevelChunk chunk, List<IplRouteRect> zones, Predicate<BlockEntity> routed) {
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (zones != null && !nearAny(zones, new AABB(be.getBlockPos()))) continue;
			if (routed.test(be)) add(be);
		}
	}

	private static boolean nearAny(List<IplRouteRect> zones, AABB box) {
		for (IplRouteRect zone : zones) if (zone.near(box, SCAN_REACH)) return true;
		return false;
	}
}
