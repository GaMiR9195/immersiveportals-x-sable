package dev.simulated_team.simulated.neoforge.service;

import dev.simulated_team.simulated.api.SimpleResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.ArrayList;
import java.util.List;

public class SimpleResourceManagerRegistryService implements SimpleResourceManager.Registry {
	public static final List<PreparableReloadListener> LISTENERS = new ArrayList<>();

	@Override
	public void registerListener(final PreparableReloadListener listener) {
		LISTENERS.add(listener);
	}
}
