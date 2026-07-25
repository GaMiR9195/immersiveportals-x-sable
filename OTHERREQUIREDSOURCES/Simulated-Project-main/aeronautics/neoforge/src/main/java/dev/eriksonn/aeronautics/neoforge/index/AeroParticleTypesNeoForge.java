package dev.eriksonn.aeronautics.neoforge.index;

import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.index.AeroParticleTypes;
import com.simibubi.create.foundation.utility.CreateLang;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public class AeroParticleTypesNeoForge {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, Aeronautics.MOD_ID);

    public static void registerEventListeners(final IEventBus modEventBus) {
        for (final AeroParticleTypes type : AeroParticleTypes.values()) {
            final String name = CreateLang.asId(type.name());
            PARTICLE_TYPES.register(name, type::get);
        }

        modEventBus.addListener(AeroParticleTypesNeoForge::registerParticleProviders);
        PARTICLE_TYPES.register(modEventBus);
    }

    public static void registerParticleProviders(final RegisterParticleProvidersEvent event) {
        AeroParticleTypes.registerClientParticles((x) -> {
            //noinspection rawtypes,unchecked
            x.getTypeFactory().get().register((ParticleType) x.getObject(), event);
        });
    }

}
