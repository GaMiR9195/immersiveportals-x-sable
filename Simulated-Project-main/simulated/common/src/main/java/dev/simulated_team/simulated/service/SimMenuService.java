package dev.simulated_team.simulated.service;

import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.LinkedTypewriterMenuCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import java.util.function.Consumer;

public interface SimMenuService {

	SimMenuService INSTANCE = ServiceUtil.load(SimMenuService.class);

	<T extends LinkedTypewriterMenuCommon> T getLoaderLinkedTypewriter(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData);

	<T extends LinkedTypewriterMenuCommon> T getLoaderLinkedTypewriter(MenuType<?> type, int id, Inventory inv, LinkedTypewriterBlockEntity be);

	void openScreen(ServerPlayer player, MenuProvider factory, Consumer<RegistryFriendlyByteBuf> extraDataWriter);
}
