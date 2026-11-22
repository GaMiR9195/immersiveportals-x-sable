package ipl.sable.render.route;

import dev.ryanhcode.sable.Sable;
import ipl.sable.compat.IplCreateContraptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * What holds a routed model: a block entity, or an entity that the portal passes of its body do
 * not draw: anything in a body's plot (Create contraptions, seats, frames) and Create contraptions
 * in the world, whose structure Flywheel instances where no portal sees it.
 */
final class IplModelHolders {

	private IplModelHolders() {}

	/** Whether the model of {@code entity} is routed like a block entity's. */
	static boolean isRoutable(Entity entity) {
		if (entity instanceof Portal || entity instanceof Player) return false;
		return Sable.HELPER.getContaining(entity) != null || IplCreateContraptions.isContraption(entity);
	}

	static boolean isRemoved(Object holder) {
		if (holder instanceof BlockEntity be) return be.isRemoved();
		return !(holder instanceof Entity entity) || entity.isRemoved();
	}

	@Nullable
	static Level level(Object holder) {
		if (holder instanceof BlockEntity be) return be.getLevel();
		return holder instanceof Entity entity ? entity.level() : null;
	}

	/** The registry key of the holder's type (one warning per type). */
	static String type(Object holder) {
		if (holder instanceof BlockEntity be) return String.valueOf(BlockEntityType.getKey(be.getType()));
		if (holder instanceof Entity entity) return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
		return holder.getClass().getSimpleName();
	}

	static BlockPos pos(Object holder) {
		if (holder instanceof BlockEntity be) return be.getBlockPos();
		return holder instanceof Entity entity ? entity.blockPosition() : BlockPos.ZERO;
	}
}
