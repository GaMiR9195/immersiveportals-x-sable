package dev.ryanhcode.offroad.neoforge.mixin_helpers;

import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.offroad.content.blocks.borehead_bearing.BoreheadAttachedStorage;
import dev.ryanhcode.offroad.content.contraptions.borehead_contraption.BoreheadBearingContraption;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.lang.ref.WeakReference;

public class WrappedWrappedMountedItemStorage implements IItemHandlerModifiable {

    private final WeakReference<Contraption> associatedContraption;
    private final IItemHandlerModifiable wrappedInv;

    public WrappedWrappedMountedItemStorage(final WeakReference<Contraption> associatedContraption, final IItemHandlerModifiable wrappedInv) {
        this.associatedContraption = associatedContraption;
        this.wrappedInv = wrappedInv;
    }

    @Override
    public void setStackInSlot(final int i, final ItemStack itemStack) {
        final Contraption contraption = this.associatedContraption.get();
        if (contraption instanceof final BoreheadBearingContraption bce && itemStack.isEmpty()) {
            ((BoreheadAttachedStorage) bce.getStorage()).invokeUnstall();
        }

        this.wrappedInv.setStackInSlot(i, itemStack);
    }

    @Override
    public int getSlots() {
        return this.wrappedInv.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(final int i) {
        return this.wrappedInv.getStackInSlot((i));
    }

    @Override
    public ItemStack insertItem(final int i, final ItemStack itemStack, final boolean b) {
        return this.wrappedInv.insertItem(i, itemStack, b);
    }

    @Override
    public ItemStack extractItem(final int i, final int i1, final boolean b) {
        final ItemStack extracted = this.wrappedInv.extractItem(i, i1, b);

        final Contraption contraption = this.associatedContraption.get();
        if (contraption instanceof final BoreheadBearingContraption bce && !extracted.isEmpty()) {
            ((BoreheadAttachedStorage) bce.getStorage()).invokeUnstall();
        }

        return extracted;
    }

    @Override
    public int getSlotLimit(final int i) {
        return this.wrappedInv.getSlotLimit(i);
    }

    @Override
    public boolean isItemValid(final int i, final ItemStack itemStack) {
        return this.wrappedInv.isItemValid(i, itemStack);
    }
}
