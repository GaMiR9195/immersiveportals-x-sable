package foundry.veil.api.event;

import foundry.veil.api.client.render.dynamicbuffer.DynamicBuffersChange;

/**
 * Fired when the set of currently active Veil dynamic buffers has changed.
 *
 * @author RyanH
 * @since 2.3.0
 */
@FunctionalInterface
public interface VeilDynamicBuffersChangedEvent {

    /**
     * Called when the set of enabled Veil dynamic buffers has changed.
     *
     * @param change   The change containing the previous and new enabled dynamic buffers
     * @since 2.3.0
     */
    void onVeilDynamicBuffersChanged(DynamicBuffersChange change);

}
