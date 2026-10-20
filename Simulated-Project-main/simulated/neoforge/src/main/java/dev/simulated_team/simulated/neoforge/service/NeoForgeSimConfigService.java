package dev.simulated_team.simulated.neoforge.service;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.infrastructure.config.CStress;
import dev.simulated_team.simulated.config.client.SimClient;
import dev.simulated_team.simulated.config.server.SimServer;
import dev.simulated_team.simulated.service.SimConfigService;
import net.createmod.catnip.config.ConfigBase;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

public class NeoForgeSimConfigService implements SimConfigService {

    public static final Map<ModConfig.Type, ConfigBase> CONFIGS = new EnumMap<>(ModConfig.Type.class);

    private static SimServer server;

    @Override
    public boolean serverLoaded() {
        return server != null && server.specification != null && server.specification.isLoaded();
    }

    private static SimClient client;

    @Override
    public boolean clientLoaded() {
        return client != null && client.specification != null && client.specification.isLoaded();
    }

    @Override
    public SimServer server() {
        return server;
    }

    @Override
    public SimClient client() {
        return client;
    }

    public static ConfigBase byType(final ModConfig.Type type) {
        return CONFIGS.get(type);
    }

    public static void registerCommon() {
        server = register(SimServer::new, ModConfig.Type.SERVER);
        client = register(SimClient::new, ModConfig.Type.CLIENT);
    }


    private static <T extends ConfigBase> T register(final Supplier<T> factory, final ModConfig.Type side) {
        final Pair<T, ModConfigSpec> specPair = (new ModConfigSpec.Builder()).configure((builder) -> {
            final T config = factory.get();
            config.registerAll(builder);
            return config;
        });
        final T config = specPair.getLeft();
        config.specification = specPair.getRight();
        CONFIGS.put(side, config);
        return config;
    }


    public static void register(final ModLoadingContext context, final ModContainer container) {
        server = register(SimServer::new, ModConfig.Type.SERVER);
        client = register(SimClient::new, ModConfig.Type.CLIENT);

        for (final Map.Entry<ModConfig.Type, ConfigBase> typeConfigBaseEntry : CONFIGS.entrySet()) {
            container.registerConfig(typeConfigBaseEntry.getKey(), typeConfigBaseEntry.getValue().specification);
        }

        final CStress stress = SimConfigService.INSTANCE.server().kinetics.stressValues;
        BlockStressValues.IMPACTS.registerProvider(stress::getImpact);
        BlockStressValues.CAPACITIES.registerProvider(stress::getCapacity);
    }
}
