package dev.eriksonn.aeronautics.neoforge.data.recipe;

import com.simibubi.create.api.data.recipe.WashingRecipeGen;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.index.AeroBlocks;
import dev.eriksonn.aeronautics.index.AeroTags;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class AeroWashingRecipes extends WashingRecipeGen {
    public AeroWashingRecipes(final PackOutput output, final CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, Aeronautics.MOD_ID);
    }

    GeneratedRecipe ENVELOPE_WASHING = this.create("envelope_washing", b -> b
            .require(AeroTags.ItemTags.SHAFTLESS_ENVELOPE)
            .output(AeroBlocks.WHITE_ENVELOPE_BLOCK.asItem())
    );

    @Override
    public @NotNull String getName() {
        return "Aero's Whimsical Washing Recipes";
    }
}
