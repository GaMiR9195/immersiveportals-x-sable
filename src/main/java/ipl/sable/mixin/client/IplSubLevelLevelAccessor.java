package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Level rebind for the client parent-flip adopt ({@link ipl.sable.client.IplClientAdopt}). */
@Pseudo
@Mixin(value = SubLevel.class, remap = false)
public interface IplSubLevelLevelAccessor {

    @Mutable
    @Accessor(value = "level", remap = false)
    void ipl$setLevel(Level level);
}
