package dev.simulated_team.simulated.neoforge.compat.jei;


import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.index.SimBlocks;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.stream.Stream;

public final class PortableEngineDyeingRecipeMaker {

    // From JEI's ShulkerBoxColoringRecipeMaker
    public static Stream<RecipeHolder<CraftingRecipe>> createRecipes() {
        final String group = "simulated.portable_engine.color";
        final ItemStack base = SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED)
                .asStack();
        final Ingredient baseIngredient = Ingredient.of(base);

        return Arrays.stream(DyeColor.values())
                .filter(dc -> dc != DyeColor.RED)
                .map(color -> {
                    final DyeItem dye = DyeItem.byColor(color);
                    final ItemStack dyeStack = new ItemStack(dye);
                    final TagKey<Item> colorTag = color.getTag();
                    final Ingredient.Value dyeList = new Ingredient.ItemValue(dyeStack);
                    final Ingredient.Value colorList = new Ingredient.TagValue(colorTag);
                    final Stream<Ingredient.Value> colorIngredientStream = Stream.of(dyeList, colorList);
                    final Ingredient colorIngredient = Ingredient.fromValues(colorIngredientStream);
                    final NonNullList<Ingredient> inputs =
                            NonNullList.of(Ingredient.EMPTY, baseIngredient, colorIngredient);
                    final Block coloredShulkerBox = SimBlocks.PORTABLE_ENGINES.get(color)
                            .get();
                    final ItemStack output = new ItemStack(coloredShulkerBox);
                    final ShapelessRecipe recipe = new ShapelessRecipe(group, CraftingBookCategory.MISC, output, inputs);
                    return new RecipeHolder<>(Simulated.path(group + "/" + color), recipe);
                });
    }

    private PortableEngineDyeingRecipeMaker() {}

}