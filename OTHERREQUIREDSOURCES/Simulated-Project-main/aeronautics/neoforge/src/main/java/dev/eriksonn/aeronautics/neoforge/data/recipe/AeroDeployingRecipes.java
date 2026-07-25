package dev.eriksonn.aeronautics.neoforge.data.recipe;

import com.simibubi.create.api.data.recipe.DeployingRecipeGen;
import com.simibubi.create.foundation.utility.DyeHelper;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.index.AeroBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class AeroDeployingRecipes extends DeployingRecipeGen {
    public AeroDeployingRecipes(final PackOutput output, final CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, Aeronautics.MOD_ID);

        for (final DyeColor color : DyeColor.values()) {
            this.create("deploying_envelope_" + color.getName(), b -> b
                    .require(DyeHelper.getWoolOfDye(color))
                    .require(Items.STICK)
                    .output(AeroBlocks.DYED_ENVELOPE_BLOCKS.get(color), 3)
            );
        }
    }

    @Override
    public @NotNull String getName() {
        return "Aero's Devious Deploying Recipes";
    }
}
