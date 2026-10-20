package dev.simulated_team.simulated.client.sections;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SimulatedSectionManager {
	private static final Map<ResourceLocation, SimulatedSection> SECTIONS = new HashMap<>();
	private static final Map<SimulatedSection, ResourceLocation> BY_SECTION = new HashMap<>();
	private static List<SimulatedSection> sortedSections = new ArrayList<>();

	public static SimulatedSection getSection(final ResourceLocation id) {
		return SECTIONS.get(id);
	}

	public static ResourceLocation getId(final SimulatedSection section) {
		return BY_SECTION.get(section);
	}

	public static List<SimulatedSection> getSections() {
		return sortedSections;
	}

	public static class ReloadListener extends SimpleJsonResourceReloadListener {

		private static final Gson GSON = new Gson();
		public ReloadListener() {
			super(GSON, "simulated_sections");
		}

		@Override
		protected void apply(final Map<ResourceLocation, JsonElement> map, final ResourceManager resourceManager, final ProfilerFiller profilerFiller) {
			SECTIONS.clear();
			BY_SECTION.clear();
			for (final Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
				final DataResult<SimulatedSection> result = SimulatedSection.CODEC.parse(JsonOps.INSTANCE, entry.getValue());

				if(result.isSuccess()) {
					final SimulatedSection tab = result.getOrThrow();
					SECTIONS.put(entry.getKey(), tab);
					BY_SECTION.put(tab, entry.getKey());
				}
			}

			sortedSections = SECTIONS.values().stream().sorted().toList();
		}
	}
}
