package dev.simulated_team.simulated.data.neoforge;

import com.simibubi.create.api.data.recipe.BaseRecipeProvider;
import dev.simulated_team.simulated.Simulated;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.SpecialRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Recipe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class SimStandardRecipeGen extends BaseRecipeProvider {

    GeneratedRecipe PORTABLE_ENGINE_DYEING = this.createSpecial(PortableEngineDyeingRecipe::new, "crafting", "portable_engine_dyeing");

    public SimStandardRecipeGen(final PackOutput output, final CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, Simulated.MOD_ID);
    }

    @Override
    public String getName() {
        return "Simulated's Surprisingly Standard Recipes";
    }

    private GeneratedRecipe createSpecial(final Function<CraftingBookCategory, Recipe<?>> builder, final String recipeType, final String path) {
        final ResourceLocation location = Simulated.path(recipeType + "/" + path);

        return this.register(consumer -> {
            final SpecialRecipeBuilder b = SpecialRecipeBuilder.special(builder);
            b.save(consumer, location.toString());
        });
    }
}
