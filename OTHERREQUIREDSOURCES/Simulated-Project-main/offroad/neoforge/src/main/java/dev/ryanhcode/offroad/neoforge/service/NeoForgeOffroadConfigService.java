package dev.ryanhcode.offroad.neoforge.service;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.infrastructure.config.CStress;
import dev.ryanhcode.offroad.config.client.OffroadClientConfig;
import dev.ryanhcode.offroad.config.server.OffroadServer;
import dev.ryanhcode.offroad.config.OffroadConfig;
import net.createmod.catnip.config.ConfigBase;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class NeoForgeOffroadConfigService implements OffroadConfig {

	public static final Map<ModConfig.Type, ConfigBase> CONFIGS = new EnumMap<>(ModConfig.Type.class);

	private static OffroadServer server;
	private static OffroadClientConfig client;

	@Override
	public OffroadServer getServerConfig() {
		return server;
	}

	@Override
	public OffroadClientConfig getClientConfig() {
		return client;
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

	public static void register(final ModContainer container) {
		server = register(OffroadServer::new, ModConfig.Type.SERVER);
		client = register(OffroadClientConfig::new, ModConfig.Type.CLIENT);

		for (final Map.Entry<ModConfig.Type, ConfigBase> typeConfigBaseEntry : CONFIGS.entrySet()) {
			container.registerConfig(typeConfigBaseEntry.getKey(), typeConfigBaseEntry.getValue().specification);
		}

		CStress stress = server.kinetics.stressValues;
		BlockStressValues.IMPACTS.registerProvider(stress::getImpact);
		BlockStressValues.CAPACITIES.registerProvider(stress::getCapacity);
	}

	@SubscribeEvent
	public static void onLoad(final ModConfigEvent.Loading event) {
		for (final ConfigBase config : CONFIGS.values()) {
			if (config.specification == event.getConfig().getSpec()) {
				config.onLoad();
			}
		}
	}

	@SubscribeEvent
	public static void onReload(final ModConfigEvent.Reloading event) {
		for (final ConfigBase config : CONFIGS.values()) {
			if (config.specification == event.getConfig().getSpec()) {
				config.onReload();
			}
		}

	}

}
