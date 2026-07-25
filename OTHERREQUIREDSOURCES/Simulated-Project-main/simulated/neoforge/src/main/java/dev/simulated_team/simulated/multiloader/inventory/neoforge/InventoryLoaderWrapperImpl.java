package dev.simulated_team.simulated.multiloader.inventory.neoforge;

import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.item.ItemHelper.ExtractionCountMode;
import dev.simulated_team.simulated.multiloader.inventory.InventoryLoaderWrapper;
import dev.simulated_team.simulated.multiloader.inventory.ItemInfoWrapper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

public class InventoryLoaderWrapperImpl extends InventoryLoaderWrapper {

    private final IItemHandler attachedInventory;

    public InventoryLoaderWrapperImpl(final IItemHandler attachedInventory) {
        this.attachedInventory = attachedInventory;
    }

    @Override
    public ItemStack extractAny(final int maxAmount, final boolean simulate, final boolean exact) {
        final ItemStack extracted = ItemHelper.extract(this.attachedInventory, $ -> true, exact ? ExtractionCountMode.EXACTLY : ExtractionCountMode.UPTO, maxAmount, simulate);
        if (this.callback != null && !extracted.isEmpty() && !simulate) {
            this.callback.accept(true);
        }

        return extracted;
    }

    @Override
    public int insertGeneral(final ItemInfoWrapper info, final int amountToInsert, final boolean simulate) {
        final ItemStack is = ItemInfoWrapper.generateFromInfo(info);
        is.setCount(amountToInsert);

        //so by default in neoforge, most mods return an EMPTY stack if they accept the entire given item stack
        //so we can't just get the count and return that to determine how much was inserted
        //if we do that, it would be opposite of what it actually is...
        final int amountInserted = amountToInsert - ItemHandlerHelper.insertItem(this.attachedInventory, is, simulate).getCount();
        if (this.callback != null && amountInserted > 0 && !simulate) {
            this.callback.accept(false);
        }

        return amountInserted;
    }

    @Override
    public ItemStack insertSlot(final ItemStack stack, final int slot, final boolean simulate) {
        final ItemStack inserted = this.attachedInventory.insertItem(slot, stack, simulate);
        if (this.callback != null && !stack.equals(inserted) && !simulate) {
            this.callback.accept(false);
        }

        return inserted;
    }

    @Override
    public int extractGeneral(final ItemInfoWrapper info, final int amountToExtract, final boolean simulate) {
        final int extractAmount = ItemHelper.extract(this.attachedInventory, $ -> $.getItem() == info.type(), ExtractionCountMode.UPTO, amountToExtract, simulate).getCount();
        if (this.callback != null && extractAmount > 0 && !simulate) {
            this.callback.accept(true);
        }

        return extractAmount;
    }

    @Override
    public ItemStack extractSlot(final int index, final int amountToExtract, final boolean simulate) {
        final ItemStack extracted = this.attachedInventory.extractItem(index, amountToExtract, simulate);
        if (this.callback != null && !extracted.isEmpty() && !simulate) {
            this.callback.accept(true);
        }

        return extracted;
    }

    @Override
    public int getContainerSize() {
        return this.attachedInventory.getSlots();
    }

    @Override
    public int getMaxStackSize() {
        return this.attachedInventory.getSlotLimit(0);
    }

    @Override
    public @NotNull ItemStack getItem(final int slot) {
        return this.attachedInventory.getStackInSlot(slot);
    }
}
