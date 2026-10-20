package dev.simulated_team.simulated.neoforge.service;

import dev.simulated_team.simulated.service.SimFluidService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

public class NeoForgeSimFluidService implements SimFluidService {
    public long mbToLoaderUnits(final long mb) {
        return mb;
    }

    @Override
    public Fluid getFluidInItem(final ItemStack stack) {
        final IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if(handler != null) {
            final FluidStack fluid = handler.getFluidInTank(0);
            if(!fluid.isEmpty()) {
                return fluid.getFluid();
            }
        }
        return null;
    }
}
