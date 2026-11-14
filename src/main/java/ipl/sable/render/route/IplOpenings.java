package ipl.sable.render.route;

import ipl.sable.client.IplStraddleRenderCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The openings of every client level for the current render frame: its global portals
 * (dimension stacking, world-wide portals) and its portal entities, plus the root route frame of
 * each level. Collected once per frame per level on demand. Render thread only.
 */
public final class IplOpenings {

	private static final Map<Level, List<IplOpening>> BY_LEVEL = new IdentityHashMap<>();
	private static final Map<Level, Map<UUID, IplOpening>> BY_ID = new IdentityHashMap<>();
	private static final Map<Level, IplRouteFrame> ROOTS = new IdentityHashMap<>();
	private static long frame = Long.MIN_VALUE;

	private IplOpenings() {}

	/** The openings of {@code level} this frame (empty for non-client levels). */
	public static List<IplOpening> of(Level level) {
		sync();
		return BY_LEVEL.computeIfAbsent(level, IplOpenings::collect);
	}

	/** Whether {@code box} (world space of {@code level}) comes within {@code margin} of one of its openings. */
	public static boolean anyNear(Level level, AABB box, double margin) {
		List<IplOpening> openings = of(level);
		if (openings.isEmpty()) return false;
		IplRouteFrame root = root(level);
		for (IplOpening o : openings) if (root.rectOf(o).near(box, margin)) return true;
		return false;
	}

	@Nullable
	static IplOpening byId(Level level, UUID id) {
		List<IplOpening> openings = of(level);
		if (openings.isEmpty()) return null;
		if (openings.size() <= 4) {
			for (IplOpening o : openings) if (o.id.equals(id)) return o;
			return null;
		}
		return BY_ID.computeIfAbsent(level, l -> {
			Map<UUID, IplOpening> map = new HashMap<>(openings.size() * 2);
			for (IplOpening o : openings) map.putIfAbsent(o.id, o);
			return map;
		}).get(id);
	}

	/** The shared root frame of models recorded in {@code level}'s world space. */
	static IplRouteFrame root(Level level) {
		sync();
		return ROOTS.computeIfAbsent(level, IplRouteFrame::root);
	}

	/** The loaded client level of {@code dimension}; never loads one. */
	@Nullable
	static ClientLevel clientLevel(ResourceKey<Level> dimension) {
		for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
			if (level.dimension().equals(dimension)) return level;
		}
		return null;
	}

	private static void sync() {
		long current = IplStraddleRenderCache.frameId();
		if (current == frame) return;
		frame = current;
		BY_LEVEL.clear();
		BY_ID.clear();
		ROOTS.clear();
	}

	private static List<IplOpening> collect(Level level) {
		if (!(level instanceof ClientLevel client)) return List.of();
		List<IplOpening> out = new ArrayList<>();
		for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) add(out, portal);
		for (Entity entity : client.entitiesForRendering()) {
			if (entity instanceof Portal portal) add(out, portal);
		}
		return out.isEmpty() ? List.of() : out;
	}

	private static void add(List<IplOpening> out, Portal portal) {
		IplOpening opening = IplOpening.of(portal);
		if (opening != null) out.add(opening);
	}
}
