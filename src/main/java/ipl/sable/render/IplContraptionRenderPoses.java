package ipl.sable.render;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.SubLevelFlwRenderState;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Sable's render poses of Create contraptions on sub-levels, kept per client level.
 *
 * <p>Sable keeps them in one map keyed by plot and prunes it against the level whose Flywheel
 * frame starts. Every client level is visualized here (portal views included), so a level seen
 * through a portal dropped the poses of the player's level: contraptions on its sub-levels
 * (windmills, bearings, trains) fell back to plot coordinates and vanished. Plot keys of
 * different levels also collide, since every level's plots start at the same origin.
 *
 * <p>Sable reads a pose without naming the level; contraption visuals name it for the duration
 * of their transform update ({@link #withVisualLevel}), which may run on Flywheel's workers.
 *
 * <p>Sable creates a pose only when a contraption joins its level while its body is known, and
 * drops it whenever the body is missing for a frame: a contraption that arrives before its body
 * (a body moved into another dimension, a body re-sent by the server) was drawn at plot
 * coordinates, far away, until it rejoined. Every visualized contraption is remembered and its
 * pose is recreated as soon as its body is there ({@link #ensure}).
 * Loaded only with Sable's Flywheel compatibility present.
 */
public final class IplContraptionRenderPoses {

	private static final Map<Level, Long2ObjectMap<SubLevelFlwRenderState>> BY_LEVEL =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static final ThreadLocal<Level> VISUAL_LEVEL = new ThreadLocal<>();
	/** Contraptions with a visual; Flywheel may update visuals on its workers. */
	private static final Set<Entity> VISUALIZED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private IplContraptionRenderPoses() {}

	/** The poses of {@code level}; without one, of the visual being updated or the player's level. */
	public static Long2ObjectMap<SubLevelFlwRenderState> of(@Nullable Level level) {
		Level key = level != null ? level : VISUAL_LEVEL.get();
		if (key == null) key = Minecraft.getInstance().level;
		if (key == null) return Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
		return BY_LEVEL.computeIfAbsent(key, k -> Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>()));
	}

	/** A contraption visual updates its transform: its pose is kept from now on. */
	public static void remember(Entity contraption) {
		VISUALIZED.add(contraption);
	}

	/** Start of {@code level}'s visualization frame (render thread): every remembered contraption on a known body has a pose. */
	public static void ensure(Level level) {
		List<Entity> contraptions;
		synchronized (VISUALIZED) {
			VISUALIZED.removeIf(Entity::isRemoved);
			contraptions = new ArrayList<>(VISUALIZED);
		}
		for (Entity contraption : contraptions) {
			if (contraption.level() != level) continue;
			SubLevel body = Sable.HELPER.getContaining(contraption);
			if (body != null) FlywheelCompatNeoForge.createRenderInfo(level, body);
		}
	}

	/** Whether the body under {@code contraption} has a render pose in its level. */
	public static boolean hasPose(Entity contraption) {
		SubLevel body = Sable.HELPER.getContaining(contraption);
		SubLevelContainer container = SubLevelContainer.getContainer(contraption.level());
		if (body == null || container == null) return false;
		ChunkPos plot = body.getPlot().plotPos;
		long key = ChunkPos.asLong(plot.x - container.getOrigin().x, plot.z - container.getOrigin().y);
		return of(contraption.level()).containsKey(key);
	}

	/** Runs a contraption visual's transform update with its level as the one poses are read from. */
	public static void withVisualLevel(Level level, Runnable update) {
		Level previous = VISUAL_LEVEL.get();
		VISUAL_LEVEL.set(level);
		try {
			update.run();
		} finally {
			VISUAL_LEVEL.set(previous);
		}
	}
}
