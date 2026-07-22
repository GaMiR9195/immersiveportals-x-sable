package dev.simulated_team.simulated.data.neoforge;

import com.simibubi.create.api.data.recipe.BaseRecipeProvider;
import dev.simulated_team.simulated.Simulated;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class SimProcessingRecipeGen extends BaseRecipeProvider {
    protected static final List<BaseRecipeProvider> GENERATORS = new ArrayList<>();
    public static DataProvider registerAll(final PackOutput output, final CompletableFuture<HolderLookup.Provider> lookupProvider) {
        GENERATORS.add(new SimFillingRecipes(output, lookupProvider));
        GENERATORS.add(new SimMechanicalCraftingRecipes(output, lookupProvider));
        GENERATORS.add(new SimSequencedAssemblyRecipes(output, lookupProvider));
        GENERATORS.add(new SimStandardRecipeGen(output, lookupProvider));
        return new DataProvider() {
            @Override
            public CompletableFuture<?> run(final CachedOutput arg) {
                return CompletableFuture.allOf(GENERATORS.stream()
                        .map(gen -> gen.run(arg))
                        .toArray(CompletableFuture[]::new));
            }

            @Override
            public String getName() {
                return "Simulated's Peculiar Processing Recipes";
            }
        };
    }
    public SimProcessingRecipeGen(final PackOutput output, final CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, Simulated.MOD_ID);
    }
}
