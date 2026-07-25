package dev.simulated_team.simulated.index;

import com.simibubi.create.AllBlocks;
import com.tterrag.registrate.util.entry.ItemProviderEntry;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.data.SimBlockStateGen;
import dev.simulated_team.simulated.ponder.new_ponder_tooltip.NewPonderTooltipManager;
import dev.simulated_team.simulated.ponder.scenes.*;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;

public class SimPonderScenes {
    public static void register(final PonderSceneRegistrationHelper<ResourceLocation> registry) {
        final PonderSceneRegistrationHelper<ItemProviderEntry<?, ?>> helper = registry.withKeyFunction(DeferredHolder::getId);

        //PHYSICS
        helper.forComponents(SimBlocks.PHYSICS_ASSEMBLER)
                .addStoryBoard("physics_assembler/intro", PhysicsAssemblerScenes::physicsAssemblerIntro)
                .addStoryBoard("physics_assembler/simulated_contraptions", PhysicsAssemblerScenes::physicsAssemblerSimulatedContraptions)
                .addStoryBoard("physics_assembler/block_properties", PhysicsAssemblerScenes::physicsAssemblerBlockProperties)
                .addStoryBoard("physics_assembler/sub_level_splitting", PhysicsAssemblerScenes::physicsAssemblerSubLevelSplitting);

        helper.forComponents(vanillaItemProvider("slime_ball"))
                .addStoryBoard("physics_assembler/sub_level_splitting", PhysicsAssemblerScenes::physicsAssemblerSubLevelSplitting);

        helper.forComponents(SimBlocks.SWIVEL_BEARING)
                .addStoryBoard("swivel_bearing/intro", SwivelBearingScenes::swivelBearingIntro)
                .addStoryBoard("swivel_bearing/unlocking", SwivelBearingScenes::swivelBearingUnlocking)
                .addStoryBoard("swivel_bearing/passthrough", SwivelBearingScenes::swivelBearingPassthrough);

        helper.forComponents(AllBlocks.SAIL)
                        .addStoryBoard("symmetric_sail/main", SymmetricSailScenes::symmetricSailMain);
        helper.forComponents(SimBlocks.WHITE_SYMMETRIC_SAIL)
                .addStoryBoard("symmetric_sail/main", SymmetricSailScenes::symmetricSailMain)
                .addStoryBoard("symmetric_sail/windmill", SymmetricSailScenes::symmetricSailWindmill);
        NewPonderTooltipManager.forItems(AllBlocks.SAIL.asItem())
                .addScenes(Simulated.path("symmetric_sail"));

        helper.forComponents(SimBlocks.ROPE_CONNECTOR, SimBlocks.ROPE_WINCH, SimItems.ROPE_COUPLING)
                .addStoryBoard("rope", RopeScenes::ropeIntro)
                .addStoryBoard("rope", RopeScenes::ropeConnections);

        helper.forComponents(AllBlocks.NOZZLE, AllBlocks.ENCASED_FAN)
                        .addStoryBoard("nozzle", KineticScenes::nozzle);
        NewPonderTooltipManager.forItems(AllBlocks.NOZZLE.asItem(), AllBlocks.ENCASED_FAN.asItem())
                .addScenes(Simulated.path("nozzle"));
        helper.forComponents(SimBlocks.DOCKING_CONNECTOR)
                        .addStoryBoard("docking_connector",DockingConnectorScenes::DockingConnector);

        //KINETICS
        helper.forComponents(SimBlocks.PORTABLE_ENGINES)
                .addStoryBoard("portable_engine", KineticScenes::portableEngine);
        helper.forComponents(SimBlocks.DIRECTIONAL_GEARSHIFT)
                .addStoryBoard("directional_gearshift", KineticScenes::directionalGearshift);
        helper.forComponents(SimBlocks.ANALOG_TRANSMISSION)
                .addStoryBoard("analog_transmission", KineticScenes::analogTransmission);
        helper.forComponents(SimBlocks.STEERING_WHEEL)
                .addStoryBoard("steering_wheel/intro", KineticScenes::steeringWheelIntro)
                .addStoryBoard("steering_wheel/comparator", KineticScenes::steeringWheelComparator);
        helper.forComponents(SimBlocks.AUGER_SHAFT, SimBlocks.AUGER_COG)
                .addStoryBoard("auger_shaft/intro", AugerShaftScenes::augerShaftIntro)
                .addStoryBoard("auger_shaft/extracting", AugerShaftScenes::augerShaftExtracting);
        helper.forComponents(SimBlocks.TORSION_SPRING)
                .addStoryBoard("torsion_spring",KineticScenes::torsionSpring);

        //REDSTONE
        helper.forComponents(SimBlocks.MODULATING_LINKED_RECEIVER)
                .addStoryBoard("redstone/modulating_receiver", RedstoneScenes::modulatingReceiver);
        helper.forComponents(SimBlocks.DIRECTIONAL_LINKED_RECEIVER)
                .addStoryBoard("redstone/directional_receiver", RedstoneScenes::directionalReceiver);
        helper.forComponents(SimBlocks.REDSTONE_ACCUMULATOR)
                .addStoryBoard("redstone/redstone_accumulator", RedstoneScenes::redstoneAccumulator);
        helper.forComponents(SimBlocks.REDSTONE_INDUCTOR)
                .addStoryBoard("redstone/redstone_inductor", RedstoneScenes::redstoneInductor);
        helper.forComponents(SimBlocks.REDSTONE_MAGNET)
                .addStoryBoard("redstone/redstone_magnet", RedstoneScenes::redstoneMagnet);
        helper.forComponents(SimBlocks.THROTTLE_LEVER)
                .addStoryBoard("redstone/throttle_lever", RedstoneScenes::throttleLever);

        //SENSORS
        helper.forComponents(SimBlocks.ALTITUDE_SENSOR)
                        .addStoryBoard("sensor/altitude_sensor", SensorScenes::altitudeSensorIntro);
        helper.forComponents(SimBlocks.OPTICAL_SENSOR)
                .addStoryBoard("sensor/lasers/optical_sensor", SensorScenes::opticalSensor);
        helper.forComponents(SimBlocks.LASER_POINTER, SimBlocks.LASER_SENSOR)
                .addStoryBoard("sensor/lasers/laser_pointer", SensorScenes::lasers);
        helper.forComponents(SimBlocks.GIMBAL_SENSOR)
                .addStoryBoard("sensor/gimbal_sensor", SensorScenes::gimbalSensor);
        helper.forComponents(SimBlocks.NAVIGATION_TABLE)
                .addStoryBoard("sensor/navigation_table", SensorScenes::navigationTable, SimPonderTags.NAVIGATION_ITEMS);
        helper.forComponents(SimBlocks.VELOCITY_SENSOR)
                .addStoryBoard("sensor/velocity_sensor", SensorScenes::velocitySensor);

        //ITEMS
        helper.forComponents(SimItems.HONEY_GLUE)
                .addStoryBoard("honey_glue/intro", HoneyGlueScenes::honeyGlueIntro)
                .addStoryBoard("honey_glue/super_glue", HoneyGlueScenes::honeyGlueSuperGlue);

    }

    private static ItemProviderEntry<Item, Item> vanillaItemProvider(final String id) {
        return new ItemProviderEntry<>(
                Simulated.getRegistrate(),
                DeferredHolder.create(ResourceKey.create(Registries.ITEM, ResourceLocation.withDefaultNamespace(id)))
        );
    }
}