package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Container rebind for the client parent-flip adopt ({@link ipl.sable.client.IplClientAdopt}). */
@Pseudo
@Mixin(value = LevelPlot.class, remap = false)
public interface IplLevelPlotContainerAccessor {

    @Mutable
    @Accessor(value = "container", remap = false)
    void ipl$setContainer(SubLevelContainer container);
}
