package dev.eriksonn.aeronautics.neoforge;


import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.neoforge.events.AeroNeoForgeCommonEvents;
import dev.eriksonn.aeronautics.neoforge.index.AeroFluidsNeoForge;
import dev.eriksonn.aeronautics.neoforge.index.AeroParticleTypesNeoForge;
import dev.eriksonn.aeronautics.neoforge.service.NeoForgeAeroConfigService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Aeronautics.MOD_ID)
public class AeronauticsNeoForge {
    public AeronauticsNeoForge(final IEventBus modBus, final ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(AeroNeoForgeCommonEvents.class);
        modBus.register(AeroNeoForgeCommonEvents.ModBusEvents.class);

        AeroParticleTypesNeoForge.registerEventListeners(modBus);
        Aeronautics.getRegistrate().registerEventListeners(modBus);

        Aeronautics.init();
        AeroFluidsNeoForge.init();

        NeoForgeAeroConfigService.register(modContainer);
    }
}
