package dev.simulated_team.simulated.multiloader.inventory.neoforge;

import dev.simulated_team.simulated.multiloader.inventory.AbstractContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;

public class ContainerWrapper<T extends AbstractContainer> implements IItemHandlerModifiable {

    private final  T container;

    public ContainerWrapper(final T container) {
        this.container = container;
    }

    @Override
    public @NotNull ItemStack insertItem(final int slot, @NotNull final ItemStack stack, final boolean simulate) {
        return this.container.insertSlot(stack, slot, simulate);
    }

    @Override
    public @NotNull ItemStack extractItem(final int slot, final int maxSize, final boolean simulate) {
        return this.container.extractSlot(slot, maxSize, simulate);
    }

    @Override
    public void setStackInSlot(final int i, @NotNull final ItemStack arg) {
        this.container.setItem(i, arg);
    }

    @Override
    public int getSlots() {
        return this.container.getContainerSize();
    }

    @Override
    public @NotNull ItemStack getStackInSlot(final int i) {
        return this.container.getItem(i);
    }

    @Override
    public int getSlotLimit(final int i) {
        return this.container.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(final int i, @NotNull final ItemStack arg) {
        return true;
    }
}