package dev.simulated_team.simulated.content.end_sea;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.network.packets.end_sea.ClientboundEndSeaPacket;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class EndSeaPhysicsData {
    private static final HashMap<ResourceKey<Level>, EndSeaPhysics> END_SEA_PHYSICS_DATA = new HashMap<>();

    public static @Nullable EndSeaPhysics of(final Level level) {
        return END_SEA_PHYSICS_DATA.get(level.dimension());
    }

    public static void physicsTick(final double substepTimeStep, final ServerLevel level) {
        final EndSeaPhysics physics = EndSeaPhysicsData.of(level);
        if (physics != null) {
            physics.physicsTick(substepTimeStep, level);
        }
    }

    public static void addKeyWithPriority(final ResourceKey<Level> dimension, final EndSeaPhysics newPhysics) {
        final EndSeaPhysics existing = END_SEA_PHYSICS_DATA.get(dimension);
        if (existing != null) {
            if (existing.priority().isEmpty()) {
                END_SEA_PHYSICS_DATA.put(dimension, newPhysics);
            } else if (newPhysics.priority().isEmpty()) {
                // pass if existing has defined priority and new doesn't
            } else if (newPhysics.priority().get() > existing.priority().get()) {
                END_SEA_PHYSICS_DATA.put(dimension, newPhysics);
            }
        } else {
            END_SEA_PHYSICS_DATA.put(dimension, newPhysics);
        }
    }

    public static void syncDataPacket(final VeilPacketManager.PacketSink sink) {
        sink.sendPacket(new ClientboundEndSeaPacket(END_SEA_PHYSICS_DATA.entrySet().stream().map(Map.Entry::getValue).toList()));
    }

    public static void handleDataPacket(final ClientboundEndSeaPacket packet) {
        END_SEA_PHYSICS_DATA.clear();
        for (final EndSeaPhysics physics : packet.physics()) {
            addKeyWithPriority(ResourceKey.create(Registries.DIMENSION, physics.dimension()), physics);
        }
    }

    public static class ReloadListener extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new Gson();
        public static final ReloadListener INSTANCE = new ReloadListener();

        public static final String NAME = "end_sea";
        public static final ResourceLocation ID = Simulated.path(NAME);

        public ReloadListener() {
            super(GSON, NAME);
        }

        @Override
        protected void apply(final Map<ResourceLocation, JsonElement> map, final ResourceManager resourceManager, final ProfilerFiller profiler) {
            END_SEA_PHYSICS_DATA.clear();

            for (final Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
                try {
                    final DataResult<EndSeaPhysics> dataResult = EndSeaPhysics.CODEC.parse(JsonOps.INSTANCE, entry.getValue());

                    if (dataResult.isError()) {
                        Simulated.LOGGER.error(String.valueOf(dataResult.error().get()));
                    }

                    final EndSeaPhysics physics = dataResult.getOrThrow();
                    final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, physics.dimension());

                    EndSeaPhysicsData.addKeyWithPriority(dimension, physics);
                } catch (final Exception e) {
                    Simulated.LOGGER.error("Error while parsing EndSeaPhysics \"{}\" : {}", entry.getKey(), e.getMessage());
                }
            }
        }

        @Override
        public String getName() {
            return NAME;
        }
    }
}
