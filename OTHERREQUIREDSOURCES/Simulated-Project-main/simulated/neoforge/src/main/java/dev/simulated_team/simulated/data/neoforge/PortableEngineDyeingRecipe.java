package dev.simulated_team.simulated.data.neoforge;

import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlock;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.index.neoforge.SimNeoForgeRecipeTypes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.Tags;

public class PortableEngineDyeingRecipe extends CustomRecipe {

    public PortableEngineDyeingRecipe(final CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(final CraftingInput input, final Level level) {
        int engines = 0;
        int dyes = 0;

        for (int i = 0; i < input.size(); ++i) {
            final ItemStack stack = input.getItem(i);
            if (!stack.isEmpty()) {
                if (Block.byItem(stack.getItem()) instanceof PortableEngineBlock) {
                    ++engines;
                } else {
                    if (!stack.is(Tags.Items.DYES))
                        return false;
                    ++dyes;
                }

                if (dyes > 1 || engines > 1) {
                    return false;
                }
            }
        }

        return engines == 1 && dyes == 1;
    }

    @Override
    public ItemStack assemble(final CraftingInput input, final HolderLookup.Provider registries) {
        ItemStack engine = ItemStack.EMPTY;
        DyeColor color = DyeColor.RED;

        for (int i = 0; i < input.size(); ++i) {
            final ItemStack stack = input.getItem(i);
            if (!stack.isEmpty()) {
                if (Block.byItem(stack.getItem()) instanceof PortableEngineBlock) {
                    engine = stack;
                } else {
                    final DyeColor color1 = DyeColor.getColor(stack);
                    if (color1 != null) {
                        color = color1;
                    }
                }
            }
        }

        final ItemStack dyedEngine = SimBlocks.PORTABLE_ENGINES.get(color)
                .asStack();
        if (!engine.isComponentsPatchEmpty()) {
            dyedEngine.applyComponents(engine.getComponentsPatch());
        }

        return dyedEngine;
    }

    @Override
    public boolean canCraftInDimensions(final int width, final int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SimNeoForgeRecipeTypes.PORTABLE_ENGINE_DYEING.getSerializer();
    }

}
