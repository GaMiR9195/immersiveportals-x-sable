package dev.eriksonn.aeronautics.index;

import dev.eriksonn.aeronautics.Aeronautics;
import foundry.veil.platform.registry.RegistrationProvider;
import foundry.veil.platform.registry.RegistryObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

public class AeroArmorMaterials {
	private static final RegistrationProvider<ArmorMaterial> REGISTRY = RegistrationProvider.get(Registries.ARMOR_MATERIAL, Aeronautics.MOD_ID);

	public static final RegistryObject<ArmorMaterial> AVIATORS_GOGGLES = REGISTRY.register("aviators_goggles", () -> new ArmorMaterial(
			new Object2ObjectOpenHashMap<>() {{
				this.put(ArmorItem.Type.HELMET, 1);
			}},
			15,
			SoundEvents.ARMOR_EQUIP_LEATHER,
			() -> Ingredient.of(Items.LEATHER),
			List.of(new ArmorMaterial.Layer(Aeronautics.path("aviators_goggles"))),
			0.0f,
			0.0f
	));

	public static void init() {}
}
