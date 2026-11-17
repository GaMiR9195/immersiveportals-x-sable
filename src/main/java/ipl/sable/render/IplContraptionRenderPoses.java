package ipl.sable.render;

import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.SubLevelFlwRenderState;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
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
 * Loaded only with Sable's Flywheel compatibility present.
 */
public final class IplContraptionRenderPoses {

	private static final Map<Level, Long2ObjectMap<SubLevelFlwRenderState>> BY_LEVEL =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static final ThreadLocal<Level> VISUAL_LEVEL = new ThreadLocal<>();

	private IplContraptionRenderPoses() {}

	/** The poses of {@code level}; without one, of the visual being updated or the player's level. */
	public static Long2ObjectMap<SubLevelFlwRenderState> of(@Nullable Level level) {
		Level key = level != null ? level : VISUAL_LEVEL.get();
		if (key == null) key = Minecraft.getInstance().level;
		if (key == null) return Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
		return BY_LEVEL.computeIfAbsent(key, k -> Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>()));
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
