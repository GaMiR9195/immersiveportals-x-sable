package ipl.sable.dim;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Read views over container membership. None of them mutates or replaces the owned list.
 *
 * <ul>
 *   <li>{@link #inWorld}: the public world view — a parent world lists its own ships plus the
 *       live hosted ships whose exact parent is that world.</li>
 *   <li>{@link #ofParent}: the storage container as seen from inside one world's frame — only
 *       the hosted ships of that world (a ship-side caller in the overworld must not
 *       enumerate Nether ships as its neighbours).</li>
 *   <li>{@link #framed}: the storage container's own processing pass — every owned ship, with
 *       the world frame of the element being processed armed while the caller handles it.</li>
 * </ul>
 */
public final class IplSubLevelReadViews {
    private IplSubLevelReadViews() {}

    public static List<SubLevel> inWorld(Level world, List<SubLevel> owned, List<? extends SubLevel> hosted) {
        if (world == null || IplDimAgnostic.isHostingLevel(world)) return owned;
        List<SubLevel> result = null;
        Set<SubLevel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(owned);
        for (var sub : hosted) {
            if (sub.isRemoved() || IplDimAgnostic.getParentLevel(sub) != world || !seen.add(sub)) continue;
            if (result == null) result = new ArrayList<>(owned);
            result.add(sub);
        }
        return result == null ? owned : Collections.unmodifiableList(result);
    }

    /** Owned storage ships whose exact parent is {@code parent}. */
    public static List<SubLevel> ofParent(List<SubLevel> owned, Level parent) {
        List<SubLevel> result = new ArrayList<>(owned.size());
        for (SubLevel sub : owned) {
            if (sub == null || sub.isRemoved()) continue;
            if (IplDimAgnostic.getParentLevel(sub) == parent) result.add(sub);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * The storage pass view: same elements and order as {@code owned}, but handing an element
     * out (iterator {@code next}, {@code get}, {@code forEach}) arms that ship's world frame
     * for the code that processes it. Iteration end restores the frame that was current when
     * the iteration began; the enclosing {@link IplProcessingPass} restores everything else.
     */
    public static List<SubLevel> framed(List<SubLevel> owned) {
        return new Framed(owned);
    }

    /**
     * The frame most recently armed by a {@link #framed} view on this thread. A frame armed
     * by an element hand-out (as opposed to a BE/physics/interaction seam) keeps enumerating
     * the full pass list, so indexed loops that re-read {@code getAllSubLevels()} per step
     * see stable indices.
     */
    private static final ThreadLocal<ServerLevel> ELEMENT_FRAME = new ThreadLocal<>();

    public static boolean isElementFrame(@Nullable ServerLevel frame) {
        return frame != null && ELEMENT_FRAME.get() == frame;
    }

    @Nullable
    static ServerLevel elementFrame() {
        return ELEMENT_FRAME.get();
    }

    static void restoreElementFrame(@Nullable ServerLevel frame) {
        if (frame == null) ELEMENT_FRAME.remove(); else ELEMENT_FRAME.set(frame);
    }

    /**
     * Rolling-frame step (see {@link IplWorldFrameContext#rollingActive}): a hosted plot was
     * resolved inside the hosting pass. Re-arm the pass-owned frame with that ship's parent,
     * unless an explicit seam armed the current frame.
     */
    static void followResolvedShip(@Nullable SubLevel sub) {
        ServerLevel current = IplWorldFrameContext.current();
        if (current != null && current != ELEMENT_FRAME.get()) return;
        ServerLevel frame = frameOf(sub);
        if (frame == null || frame == current) return;
        restoreElementFrame(frame);
        IplWorldFrameContext.pop(frame);
    }

    private static void arm(@Nullable SubLevel sub) {
        ServerLevel frame = frameOf(sub);
        restoreElementFrame(frame);
        IplWorldFrameContext.pop(frame);
    }

    private static void restore(@Nullable ServerLevel frame, @Nullable ServerLevel element) {
        restoreElementFrame(element);
        IplWorldFrameContext.pop(frame);
    }

    @Nullable
    private static ServerLevel frameOf(@Nullable SubLevel sub) {
        if (sub == null || sub.isRemoved() || !IplDimAgnostic.isHosted(sub)) return null;
        return IplDimAgnostic.getServerParentLevel(sub);
    }

    private static final class Framed extends AbstractList<SubLevel> implements RandomAccess {
        private final List<SubLevel> owned;

        Framed(List<SubLevel> owned) {
            this.owned = owned;
        }

        @Override
        public SubLevel get(int index) {
            SubLevel sub = this.owned.get(index);
            arm(sub);
            return sub;
        }

        @Override
        public int size() {
            return this.owned.size();
        }

        @Override
        public boolean contains(Object o) {
            return this.owned.contains(o);
        }

        @Override
        public int indexOf(Object o) {
            return this.owned.indexOf(o);
        }

        @Override
        public Iterator<SubLevel> iterator() {
            final ServerLevel before = IplWorldFrameContext.current();
            final ServerLevel beforeElement = ELEMENT_FRAME.get();
            final Iterator<SubLevel> it = this.owned.iterator();
            return new Iterator<>() {
                private boolean restored;

                @Override
                public boolean hasNext() {
                    boolean next = it.hasNext();
                    if (!next && !this.restored) {
                        this.restored = true;
                        restore(before, beforeElement);
                    }
                    return next;
                }

                @Override
                public SubLevel next() {
                    SubLevel sub = it.next();
                    arm(sub);
                    return sub;
                }
            };
        }

        @Override
        public void forEach(Consumer<? super SubLevel> action) {
            ServerLevel before = IplWorldFrameContext.current();
            ServerLevel beforeElement = ELEMENT_FRAME.get();
            try {
                for (SubLevel sub : this.owned) {
                    arm(sub);
                    action.accept(sub);
                }
            } finally {
                restore(before, beforeElement);
            }
        }
    }
}
