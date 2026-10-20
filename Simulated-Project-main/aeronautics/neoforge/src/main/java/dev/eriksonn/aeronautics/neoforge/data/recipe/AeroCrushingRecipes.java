package dev.eriksonn.aeronautics.neoforge.data.recipe;

import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.index.AeroItems;
import com.simibubi.create.api.data.recipe.CrushingRecipeGen;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class AeroCrushingRecipes extends CrushingRecipeGen {
	public AeroCrushingRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries, Aeronautics.MOD_ID);
	}

	GeneratedRecipe END_STONE_POWDER = create("end_stone_powder", b -> b
			.duration(250)
			.require(Blocks.END_STONE)
			.output(0.5f, Blocks.END_STONE)
			.output(AeroItems.ENDSTONE_POWDER)
	);

	@Override
	public @NotNull String getName() {
		return "Aero's Captivating Crushing Recipes";
	}
}
