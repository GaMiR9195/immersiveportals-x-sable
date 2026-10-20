package dev.eriksonn.aeronautics.content.items;

import com.simibubi.create.content.equipment.armor.BaseArmorItem;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.index.AeroArmorMaterials;
import dev.eriksonn.aeronautics.index.AeroItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

public class AviatorsGogglesItem extends BaseArmorItem {
	public static final Type TYPE = Type.HELMET;
	private static final ResourceLocation TEXTURE = Aeronautics.path("aviators_goggles");

	public AviatorsGogglesItem(final Properties properties) {
		super(AeroArmorMaterials.AVIATORS_GOGGLES.asHolder(), TYPE, properties, TEXTURE);
		GogglesItem.addIsWearingPredicate(player -> AeroItems.AVIATORS_GOGGLES.isIn(player.getItemBySlot(EquipmentSlot.HEAD)));
	}
}
