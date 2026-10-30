package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * One sequence over every straddle projection of a pass, arming each projection's mapped-pose
 * render state exactly while the consumer is drawing that element.
 *
 * <p>Why this exists: {@code SubLevelRenderDispatcher.renderAfterSections} drains a SHARED
 * queue of pending single-block layers and clears it. Calling it once per projection drew all
 * but the first from an already emptied queue, so single-block sub-levels (rope ends, one-block
 * contraptions) were missing from destination projections.
 *
 * <p>The dispatcher builds one buffer per queued layer, re-iterating this sequence once per
 * layer, and calls {@code renderSingleBlock} immediately after taking each element from the
 * iterator. Arming inside {@code next()} is therefore exactly scoped to the element being baked.
 *
 * <p>A consumer may stop early (empty buffer, exception). Callers must therefore invoke
 * {@link #disarm()} in a {@code finally} block; it is idempotent.
 */
public final class IplProjectionRenderSequence implements Iterable<ClientSubLevel> {

    private final List<IplClientProjections.StraddleProjection> projections;
    private Cursor active;

    public IplProjectionRenderSequence(List<IplClientProjections.StraddleProjection> projections) {
        this.projections = projections;
    }

    public boolean isEmpty() {
        return projections.isEmpty();
    }

    @Override
    public Iterator<ClientSubLevel> iterator() {
        disarm();
        active = new Cursor();
        return active;
    }

    /** Clears a projection state left armed by an abandoned iteration. Safe to call twice. */
    public void disarm() {
        if (active != null) {
            active.disarm();
            active = null;
        }
    }

    private final class Cursor implements Iterator<ClientSubLevel> {
        private int index;
        private boolean armed;

        @Override
        public boolean hasNext() {
            if (index < projections.size()) return true;
            disarm();
            return false;
        }

        @Override
        public ClientSubLevel next() {
            disarm();
            if (index >= projections.size()) throw new NoSuchElementException();
            IplClientProjections.StraddleProjection projection = projections.get(index++);
            IplStraddleRenderState.set(projection);
            armed = true;
            return projection.sub();
        }

        void disarm() {
            if (armed) {
                IplStraddleRenderState.clear();
                armed = false;
            }
        }
    }
}
