package foundry.veil.fabric.event;

import foundry.veil.api.event.VeilRegisterInspectorsEvent;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Fired to register ImGui inspectors. Only called when ImGuiMC is loaded and editors are fully enabled.
 *
 * @since 4.0.0
 */
@FunctionalInterface
public interface FabricVeilRegisterInspectorsEvent extends VeilRegisterInspectorsEvent {

    Event<VeilRegisterInspectorsEvent> EVENT = EventFactory.createArrayBacked(VeilRegisterInspectorsEvent.class, events -> registry -> {
        for (VeilRegisterInspectorsEvent event : events) {
            event.onRegisterInspectors(registry);
        }
    });
}
