package dev.simulated_team.simulated.content.navigation_targets.lodestone_compass_compatability;

import dev.engine_room.flywheel.lib.util.LevelAttached;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.joml.Vector3d;

import java.util.UUID;

public class ClientLodestonePositions {

	public static final LevelAttached<ClientLodestonePositions> clientPositions = new LevelAttached<>(level -> new ClientLodestonePositions());
	public final Object2ObjectOpenHashMap<UUID, Vector3d> CLIENT_LODESTONE_MAP = new Object2ObjectOpenHashMap<>();


}
