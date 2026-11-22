package ipl.sable.mixin;

import ipl.sable.compat.IplCreateContraptions;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** The block a controlled contraption (bearing, piston, pulley) hangs on. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public abstract class ControlledContraptionEntityMixin implements IplCreateContraptions.ControlledContraptionAccess {

	@Shadow
	protected BlockPos controllerPos;

	@Override
	@Nullable
	public BlockPos ipl$controllerPos() {
		return controllerPos;
	}
}
