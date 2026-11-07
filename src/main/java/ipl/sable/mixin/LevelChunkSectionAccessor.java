package ipl.sable.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the non-air block count a section already keeps, so material is counted without a block scan. */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {

    @Accessor("nonEmptyBlockCount")
    short ipl$getNonEmptyBlockCount();
}
