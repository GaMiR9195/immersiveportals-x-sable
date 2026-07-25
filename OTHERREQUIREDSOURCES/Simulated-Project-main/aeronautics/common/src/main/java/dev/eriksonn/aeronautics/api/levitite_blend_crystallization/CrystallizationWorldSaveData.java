package dev.eriksonn.aeronautics.api.levitite_blend_crystallization;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public class CrystallizationWorldSaveData extends SavedData {
	public static final String ID = "aeronautics_levitite_data";
	
	Level level;
	
	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag list = new ListTag();
		LevititeCrystallizerManager.saveData(list, level);
		tag.put("Levitite Manager Data", list);
		
		return tag;
	}

	public static CrystallizationWorldSaveData load(ServerLevel level, CompoundTag tag, final HolderLookup.Provider registries) {
		CrystallizationWorldSaveData data = new CrystallizationWorldSaveData();
		data.level = level;

		LevititeCrystallizerManager.loadData(tag, level);

		return data;
	}

	public static CrystallizationWorldSaveData get(ServerLevel level) {
		CrystallizationWorldSaveData data = level.getChunkSource().getDataStorage().computeIfAbsent(
				new SavedData.Factory<>(CrystallizationWorldSaveData::new, (nbt, lookup) -> load(level, nbt, lookup), null),
				CrystallizationWorldSaveData.ID);

		data.level = level;
		return data;
	}
}
