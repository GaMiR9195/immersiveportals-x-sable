package dev.simulated_team.simulated.neoforge.service;

import dev.simulated_team.simulated.service.SimEntityDataSerialization;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class NeoForgeSimEntityDataSerialization implements SimEntityDataSerialization {

    private static final DeferredRegister<EntityDataSerializer<?>> REGISTER = DeferredRegister.create(NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, "simulated");

    public static void register(final IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }

    @Override
    public <A, T extends EntityDataSerializer<A>> void registerDataSerializer(final String name, final T serializer) {
        REGISTER.register(name, () -> serializer);
    }
}
