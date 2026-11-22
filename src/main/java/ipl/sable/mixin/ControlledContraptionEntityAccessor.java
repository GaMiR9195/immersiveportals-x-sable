package ipl.sable.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block a controlled contraption (bearing, piston, pulley) hangs on. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public interface ControlledContraptionEntityAccessor {

    @Accessor("controllerPos")
    BlockPos ipl$controllerPos();

    @Accessor("controllerPos")
    void ipl$setControllerPos(BlockPos pos);
}
