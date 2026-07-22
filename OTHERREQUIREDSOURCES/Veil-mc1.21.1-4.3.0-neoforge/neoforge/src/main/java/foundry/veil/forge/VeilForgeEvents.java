package foundry.veil.forge;

import foundry.veil.Veil;
import foundry.veil.ext.MinecraftServerExtension;
import foundry.veil.impl.TickTaskSchedulerImpl;
import foundry.veil.impl.command.VeilCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
@EventBusSubscriber(modid = Veil.MODID)
public class VeilForgeEvents {

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Pre event) {
        TickTaskSchedulerImpl scheduler = ((MinecraftServerExtension) event.getServer()).veil$getScheduler();
        if (scheduler != null) {
            scheduler.run();
        }
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        TickTaskSchedulerImpl scheduler = ((MinecraftServerExtension) event.getServer()).veil$getScheduler();
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        VeilCommand.register(event.getDispatcher());
    }
}
