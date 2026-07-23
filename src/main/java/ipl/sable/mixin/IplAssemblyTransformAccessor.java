package ipl.sable.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to Sable's assembly transform for the disassembly portal-restore hook. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper$AssemblyTransform", remap = false)
public interface IplAssemblyTransformAccessor {

    @Accessor(value = "anchorPos", remap = false)
    BlockPos ipl$anchorPos();

    @Accessor(value = "resultingAnchorPos", remap = false)
    BlockPos ipl$resultingAnchorPos();

    @Accessor(value = "rotation", remap = false)
    Rotation ipl$rotation();
}
