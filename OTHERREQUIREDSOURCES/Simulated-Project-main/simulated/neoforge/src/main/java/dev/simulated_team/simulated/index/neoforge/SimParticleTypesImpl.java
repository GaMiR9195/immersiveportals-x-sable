package dev.simulated_team.simulated.index.neoforge;

import com.simibubi.create.foundation.utility.CreateLang;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.index.SimParticleTypes;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public class SimParticleTypesImpl {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, Simulated.MOD_ID);

    public static void register(final IEventBus modEventBus) {
        for (final SimParticleTypes type : SimParticleTypes.values()) {
            final String name = CreateLang.asId(type.name());
            PARTICLE_TYPES.register(name, () -> {
                return type.get();
            });
        }

        modEventBus.addListener(SimParticleTypesImpl::registerParticleProviders);
        PARTICLE_TYPES.register(modEventBus);
    }

    public static void registerParticleProviders(final RegisterParticleProvidersEvent event) {
        SimParticleTypes.registerClientParticles((x) -> {
            x.getTypeFactory().get().register((ParticleType) x.getObject(), event);
        });
    }
}
