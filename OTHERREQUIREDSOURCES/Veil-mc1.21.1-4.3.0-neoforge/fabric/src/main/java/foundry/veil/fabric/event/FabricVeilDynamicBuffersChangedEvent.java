package foundry.veil.fabric.event;

import foundry.veil.api.event.VeilDynamicBuffersChangedEvent;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Fired when the set of currently active Veil dynamic buffers has changed.
 *
 * @author RyanH
 */
@FunctionalInterface
public interface FabricVeilDynamicBuffersChangedEvent extends VeilDynamicBuffersChangedEvent {

    Event<VeilDynamicBuffersChangedEvent> EVENT = EventFactory.createArrayBacked(VeilDynamicBuffersChangedEvent.class, events -> (change) -> {
        for (VeilDynamicBuffersChangedEvent event : events) {
            event.onVeilDynamicBuffersChanged(change);
        }
    });
}
