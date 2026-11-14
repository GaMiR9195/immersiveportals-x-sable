package ipl.sable.render.route;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where a part of a model is drawn: the level it is in and the map from route space (the world
 * space of the level the model is recorded in) into that level's world space. The root frame is
 * the model's own level; passing an opening of a frame leads to that opening's child frame, so a
 * frame is a path of portals and nested portals (dimension stacking, portals behind portals) are
 * plain deeper frames. Crossing a frame's exit face leads back to its parent.
 *
 * <p>Frames live for one render frame ({@link IplOpenings#root}); children and placed openings
 * are memoized, so all models of a level share one tree. Render thread only.
 */
final class IplRouteFrame {

	/** Deepest path followed; deeper passages stay in the last frame (portal loops). */
	static final int MAX_DEPTH = 8;

	@Nullable final IplRouteFrame parent;
	/** The opening of {@link #parent} that leads here. */
	@Nullable final IplOpening entry;
	final Level level;
	/** Route space → this frame's level. */
	final Matrix4d toLevel;
	/** This frame's level → route space. */
	final Matrix4d fromLevel;
	final int depth;

	private final Map<UUID, IplRouteFrame> children = new HashMap<>(2);
	private final Map<IplOpening, IplRouteRect> rects = new IdentityHashMap<>();

	private IplRouteFrame(@Nullable IplRouteFrame parent, @Nullable IplOpening entry, Level level, Matrix4d toLevel) {
		this.parent = parent;
		this.entry = entry;
		this.level = level;
		this.toLevel = toLevel;
		this.fromLevel = new Matrix4d(toLevel).invert();
		this.depth = parent == null ? 0 : parent.depth + 1;
	}

	static IplRouteFrame root(Level level) {
		return new IplRouteFrame(null, null, level, new Matrix4d());
	}

	/**
	 * The frame reached by passing {@code opening} (one of this frame's level) from its front;
	 * null when it leads nowhere drawable (level not loaded, path too deep).
	 */
	@Nullable
	IplRouteFrame through(IplOpening opening) {
		if (entry != null && opening.leadsBackThrough(entry)) return parent;
		if (depth >= MAX_DEPTH) return null;
		IplRouteFrame child = children.get(opening.id);
		if (child != null) return child;
		Level destination = IplOpenings.clientLevel(opening.destination);
		if (destination == null) return null;
		child = new IplRouteFrame(this, opening, destination, new Matrix4d(opening.transform).mul(toLevel));
		children.put(opening.id, child);
		return child;
	}

	/** {@code opening} of this frame's level placed in route space. */
	IplRouteRect rectOf(IplOpening opening) {
		return rects.computeIfAbsent(opening, o -> IplRouteRect.of(o, toLevel, fromLevel));
	}
}
