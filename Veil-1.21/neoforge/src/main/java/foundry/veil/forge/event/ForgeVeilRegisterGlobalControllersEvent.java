package foundry.veil.forge.event;

import foundry.veil.api.event.VeilRegisterGlobalControllersEvent;
import foundry.veil.api.flare.modifier.ControllerManager;
import foundry.veil.api.flare.modifier.GlobalController;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * Fired to register global controllers to be used when rendering Flare effects.
 *
 * @author GuyApooye
 * @see ControllerManager
 * @since 2.5.0
 */
public class ForgeVeilRegisterGlobalControllersEvent extends Event implements IModBusEvent, VeilRegisterGlobalControllersEvent.Registry {

    private final VeilRegisterGlobalControllersEvent.Registry registry;

    public ForgeVeilRegisterGlobalControllersEvent(VeilRegisterGlobalControllersEvent.Registry registry) {
        this.registry = registry;
    }

    @Override
    public void registerGlobalController(GlobalController globalController) {
        this.registry.registerGlobalController(globalController);
    }
}
