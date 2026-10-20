package dev.simulated_team.simulated.neoforge.service;

import dev.simulated_team.simulated.service.SimAssemblyService;
import net.minecraft.world.level.block.state.BlockState;

public class NeoForgeSimAssemblyService implements SimAssemblyService {

	@Override
	public boolean canStickTo(final BlockState stateA, final BlockState stateB) {
		return stateA.canStickTo(stateB);
	}

}
