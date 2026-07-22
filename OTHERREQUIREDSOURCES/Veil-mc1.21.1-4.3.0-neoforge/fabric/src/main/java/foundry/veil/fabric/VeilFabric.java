package foundry.veil.fabric;

import foundry.veil.Veil;
import foundry.veil.impl.command.VeilCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class VeilFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Veil.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> VeilCommand.register(dispatcher));
    }
}
