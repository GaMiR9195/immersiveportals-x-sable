package foundry.veil.forge;

import foundry.veil.Veil;
import foundry.veil.impl.command.VeilCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
@Mod(Veil.MODID)
public class VeilForge {

    public VeilForge() {
        Veil.init();
    }
}
