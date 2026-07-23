package ipl.sable.duck;

import dev.ryanhcode.sable.sublevel.SubLevel;

/**
 * Client-side container surgery for the parent-flip adopt: move an EXISTING
 * {@code ClientSubLevel} (with its plot, chunks, interpolator and compiled render data)
 * between two client levels' containers without running the allocation/removal cascades.
 * Implemented by {@link ipl.sable.mixin.client.IplClientContainerAdoptMixin}.
 */
public interface IplClientContainerAdoptDuck {

    /** Remove {@code sub} from this container's bookkeeping only (no removal cascade). */
    void ipl$detachKeepingState(SubLevel sub);

    /** Insert an existing {@code sub} into this container's bookkeeping at its plot slot. */
    void ipl$attachExisting(SubLevel sub);
}
