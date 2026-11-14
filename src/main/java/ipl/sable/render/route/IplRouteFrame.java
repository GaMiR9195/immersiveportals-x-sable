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
 * <p>Frames live for one render frame ({@link IplOpenings#root}); children, placed openings and
 * resolved keys are memoized, so all models of a level share one tree. {@link #key} identifies the
 * frame across render frames. Render thread only.
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
	/** Stable identity: the portal path from the root. */
	final IplFrameKey key;

	private final Map<UUID, IplRouteFrame> children = new HashMap<>(2);
	private final Map<IplOpening, IplRouteRect> rects = new IdentityHashMap<>();
	/** Root only: keys resolved this render frame (null values: unresolvable). */
	@Nullable private Map<IplFrameKey, IplRouteFrame> resolved;
	@Nullable private IplRouteRect entryRect;

	private IplRouteFrame(@Nullable IplRouteFrame parent, @Nullable IplOpening entry, Level level, Matrix4d toLevel) {
		this.parent = parent;
		this.entry = entry;
		this.level = level;
		this.toLevel = toLevel;
		this.fromLevel = new Matrix4d(toLevel).invert();
		this.depth = parent == null ? 0 : parent.depth + 1;
		this.key = parent == null || entry == null ? IplFrameKey.ROOT : parent.key.child(entry.id);
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

	/** Whether {@code opening} of this frame's level is the exit face of {@link #entry} (leads back to the parent). */
	boolean isExit(IplOpening opening) {
		return entry != null && opening.leadsBackThrough(entry);
	}

	/** {@code opening} of this frame's level placed in route space. */
	IplRouteRect rectOf(IplOpening opening) {
		return rects.computeIfAbsent(opening, o -> IplRouteRect.of(o, toLevel, fromLevel));
	}

	/** The entry of this frame placed in route space (its front faces the parent); null at the root. */
	@Nullable
	IplRouteRect entryRect() {
		if (parent == null || entry == null) return null;
		if (entryRect == null) entryRect = parent.rectOf(entry);
		return entryRect;
	}

	/** The root of this frame's tree. */
	IplRouteFrame root() {
		IplRouteFrame f = this;
		while (f.parent != null) f = f.parent;
		return f;
	}

	/**
	 * The frame of {@code key} in this frame's tree (keys are paths from the root); null when a
	 * portal of the path is gone, its level is not loaded or the path is too deep.
	 */
	@Nullable
	IplRouteFrame resolve(IplFrameKey key) {
		IplRouteFrame root = root();
		if (key.isRoot()) return root;
		if (root.resolved == null) root.resolved = new HashMap<>();
		if (root.resolved.containsKey(key)) return root.resolved.get(key);
		IplRouteFrame result = null;
		IplRouteFrame up = key.parent == null ? null : resolve(key.parent);
		if (up != null && key.entry != null) {
			IplOpening opening = IplOpenings.byId(up.level, key.entry);
			IplRouteFrame next = opening == null ? null : up.through(opening);
			if (next != null && next.parent == up) result = next;
		}
		root.resolved.put(key, result);
		return result;
	}

	/** The deepest resolvable frame on {@code key}'s path (the root at worst). */
	IplRouteFrame resolveOrAncestor(IplFrameKey key) {
		for (IplFrameKey k = key; k != null; k = k.parent) {
			IplRouteFrame f = resolve(k);
			if (f != null) return f;
		}
		return root();
	}

	/**
	 * The frame reached from here by going back through {@code entry} (an opening of another
	 * level that leads into this one): through the opening of this level that is its exit face.
	 * Used to re-express labels after a body teleported. Null when there is no such opening.
	 */
	@Nullable
	IplRouteFrame back(IplOpening entry) {
		for (IplOpening o : IplOpenings.of(level)) {
			if (o.leadsBackThrough(entry)) return through(o);
		}
		return null;
	}
}
