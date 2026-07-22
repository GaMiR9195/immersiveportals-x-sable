package foundry.veil.forge.event;

import foundry.veil.api.client.editor.EditorManager;
import foundry.veil.api.client.editor.Inspector;
import foundry.veil.api.event.VeilRegisterInspectorsEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * Fired to register ImGui inspectors. Only called when ImGuiMC is loaded and editors are fully enabled.
 *
 * @since 4.0.0
 */
public class ForgeVeilRegisterInspectorsEvent extends Event implements IModBusEvent, VeilRegisterInspectorsEvent.Registry {

    private final VeilRegisterInspectorsEvent.Registry registry;

    public ForgeVeilRegisterInspectorsEvent(VeilRegisterInspectorsEvent.Registry registry) {
        this.registry = registry;
    }

    @Override
    public EditorManager editorManager() {
        return this.registry.editorManager();
    }

    @Override
    public void registerInspector(Inspector inspector) {
        this.registry.registerInspector(inspector);
    }
}
