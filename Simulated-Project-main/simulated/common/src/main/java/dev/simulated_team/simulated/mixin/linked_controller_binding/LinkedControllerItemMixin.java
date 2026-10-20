package dev.simulated_team.simulated.mixin.linked_controller_binding;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerItem;
import com.tterrag.registrate.util.entry.BlockEntry;
import dev.simulated_team.simulated.index.SimBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LinkedControllerItem.class)
public abstract class LinkedControllerItemMixin {
	@WrapOperation(method = "onItemUseFirst", at = @At(value = "INVOKE", target = "Lcom/tterrag/registrate/util/entry/BlockEntry;has(Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 1))
	private <T extends Block> boolean simulated$onItemUseFirst(final BlockEntry<T> instance, final BlockState state, final Operation<Boolean> original) {
		return original.call(instance, state) || SimBlocks.MODULATING_LINKED_RECEIVER.has(state) || SimBlocks.DIRECTIONAL_LINKED_RECEIVER.has(state);
	}
}
