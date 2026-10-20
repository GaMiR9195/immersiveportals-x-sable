package dev.simulated_team.simulated.data.neoforge;

import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.data.advancements.SimAdvancements;
import dev.simulated_team.simulated.index.SimTags;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.concurrent.CompletableFuture;

// advancements are written to use the traditional datagen entrypoint, and only need to be ran on one side
public class SimDatagen {
    public static void gatherDataHighPriority(final GatherDataEvent event) {
        if (event.getMods().contains(Simulated.MOD_ID))
            SimTags.addGenerators();
    }
    public static void gatherData(final GatherDataEvent event) {
        final DataGenerator generator = event.getGenerator();
        final PackOutput output = generator.getPackOutput();
        final CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();

        generator.addProvider(event.includeServer(), new SimAdvancements(output, lookupProvider));
        generator.addProvider(event.includeServer(), SimProcessingRecipeGen.registerAll(output, lookupProvider));
    }
}
