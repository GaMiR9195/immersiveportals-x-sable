package dev.eriksonn.aeronautics.neoforge.service;

import dev.eriksonn.aeronautics.neoforge.index.AeroFluidsNeoForge;
import dev.eriksonn.aeronautics.service.AeroLevititeService;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

public class NeoForgeAeroLevititeService implements AeroLevititeService {

    @Override
    public Item getBucket() {
        return AeroFluidsNeoForge.LEVITITE_BLEND.getBucket().orElseThrow();
    }

    @Override
    public Fluid getFluid() {
        return AeroFluidsNeoForge.LEVITITE_BLEND.getSource();
    }
}
