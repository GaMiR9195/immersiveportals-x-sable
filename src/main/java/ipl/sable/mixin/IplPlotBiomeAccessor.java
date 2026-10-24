package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The plot's persisted biome, not the storage dimension's generator biome. */
@Pseudo
@Mixin(value = LevelPlot.class, remap = false)
public interface IplPlotBiomeAccessor {
    @Accessor("biome")
    ResourceKey<Biome> ipl$getBiome();
}