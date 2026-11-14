package ipl.sable.render.route;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a route frame that outlives one render frame: the path of portals from the root of
 * a model's level ({@link IplRouteFrame} objects are rebuilt every frame). The labels a model
 * keeps between frames ({@link IplRouteMemory}) are keys; they are resolved against the frame
 * tree of the current frame. Immutable.
 */
final class IplFrameKey {

	static final IplFrameKey ROOT = new IplFrameKey(null, null);

	@Nullable final IplFrameKey parent;
	/** The portal passed from {@link #parent}; null at the root. */
	@Nullable final UUID entry;
	final int depth;
	private final int hash;

	private IplFrameKey(@Nullable IplFrameKey parent, @Nullable UUID entry) {
		this.parent = parent;
		this.entry = entry;
		this.depth = parent == null ? 0 : parent.depth + 1;
		this.hash = parent == null ? 0x2545F491 : 31 * parent.hash + Objects.hashCode(entry);
	}

	IplFrameKey child(UUID portal) {
		return new IplFrameKey(this, portal);
	}

	boolean isRoot() {
		return parent == null;
	}

	/** The ancestor of this key at {@code depth} (this key itself at its own depth). */
	IplFrameKey ancestorAt(int depth) {
		IplFrameKey k = this;
		while (k.depth > depth && k.parent != null) k = k.parent;
		return k;
	}

	/** Deepest key both paths share. */
	static IplFrameKey common(IplFrameKey a, IplFrameKey b) {
		IplFrameKey x = a.ancestorAt(Math.min(a.depth, b.depth));
		IplFrameKey y = b.ancestorAt(x.depth);
		while (!x.equals(y) && x.parent != null && y.parent != null) {
			x = x.parent;
			y = y.parent;
		}
		return x;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof IplFrameKey k) || k.hash != hash || k.depth != depth) return false;
		return Objects.equals(entry, k.entry) && Objects.equals(parent, k.parent);
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public String toString() {
		return parent == null ? "root" : parent + ">" + entry.toString().substring(0, 8);
	}
}
