package foundry.veil.forge.event;

import foundry.veil.api.client.render.dynamicbuffer.DynamicBuffersChange;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * Fired when the set of currently active Veil dynamic buffers has changed.
 *
 * @author RyanH
 * @since 2.3.0
 */
public class ForgeVeilDynamicBuffersChangedEvent extends Event implements IModBusEvent {

    private final DynamicBuffersChange change;

    public ForgeVeilDynamicBuffersChangedEvent(DynamicBuffersChange change) {
        this.change = change;
    }

    /**
     * @return the change to currently enabled dynamic buffers
     */
    public DynamicBuffersChange getChange() {
        return this.change;
    }
}
