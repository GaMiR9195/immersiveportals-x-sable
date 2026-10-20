package dev.eriksonn.aeronautics.neoforge.data.recipe;

import com.simibubi.create.api.data.recipe.BaseRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AeroProcessingRecipeGen {
	protected static List<BaseRecipeProvider> GENERATORS = new ArrayList<>();

	public static DataProvider registerAll(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
		GENERATORS.add(new AeroMixingRecipes(output, lookupProvider));
		GENERATORS.add(new AeroCrushingRecipes(output, lookupProvider));
		GENERATORS.add(new AeroMechanicalCraftingRecipes(output, lookupProvider));
		GENERATORS.add(new AeroWashingRecipes(output, lookupProvider));
		GENERATORS.add(new AeroDeployingRecipes(output, lookupProvider));

		return new DataProvider() {
			@Override
			public CompletableFuture<?> run(CachedOutput cachedOutput) {
				return CompletableFuture.allOf(GENERATORS.stream().map(gen -> gen.run(cachedOutput)).toArray(CompletableFuture[]::new));
			}

			@Override
			public String getName() {
				return "Aero's Perfect Processing Recipes";
			}
		};
	}
}
