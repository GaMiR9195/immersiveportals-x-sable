package foundry.veil.api.event;

import foundry.veil.api.client.editor.EditorManager;
import foundry.veil.api.client.editor.Inspector;

/**
 * Fired to register ImGui inspectors. Only called when ImGuiMC is loaded and editors are fully enabled.
 *
 * @since 4.0.0
 */
@FunctionalInterface
public interface VeilRegisterInspectorsEvent {

    /**
     * Registers global controllers.
     *
     * @param registry The registry to add global controllers to
     */
    void onRegisterInspectors(Registry registry);

    /**
     * Registers ImGui inspectors.
     */
    interface Registry {

        /**
         * @return The {@link EditorManager} instance
         */
        EditorManager editorManager();

        /**
         * Registers the specified inspector to the {@link EditorManager}.
         *
         * @param inspector The inspector to register.
         */
        void registerInspector(Inspector inspector);
    }
}
