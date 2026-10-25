package ipl.sable.mixin;

import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw delegate of Sable's inclusive entity getter (see {@link ipl.sable.dim.IplPlotEntityStorage}). */
@Mixin(value = SubLevelInclusiveLevelEntityGetter.class, remap = false)
public interface IplInclusiveEntityGetterAccessor {

    @Accessor("delegate")
    LevelEntityGetter<?> ipl$getDelegate();
}
