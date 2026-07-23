package dev.simulated_team.simulated.content.navigation_targets.lodestone_compass_compatability;

import net.minecraft.nbt.CompoundTag;
import org.joml.Vector3d;

import java.util.UUID;

public record LodestoneInformation(UUID id, Vector3d projectedPos) {
	public CompoundTag saveAsCompound() {
		final CompoundTag data = new CompoundTag();
		data.putUUID("trackerID", this.id);

		return data;
	}

	public static LodestoneInformation loadFromCompound(final CompoundTag tag) {
		return new LodestoneInformation(tag.getUUID("trackerID"), new Vector3d());
	}
}
