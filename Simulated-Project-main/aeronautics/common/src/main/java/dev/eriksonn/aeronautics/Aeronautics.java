package dev.eriksonn.aeronautics;

import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.item.TooltipModifier;
import dev.simulated_team.simulated.util.SimColors;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.eriksonn.aeronautics.data.AeroLang;
import dev.eriksonn.aeronautics.events.AeronauticsCommonEvents;
import dev.eriksonn.aeronautics.index.*;
import dev.eriksonn.aeronautics.network.AeroPacketManager;
import dev.eriksonn.aeronautics.registry.AeroRegistrate;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Aeronautics {
	public static final String MOD_ID = "aeronautics";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final NonNullSupplier<AeroRegistrate> REGISTRATE = NonNullSupplier.lazy(() ->
			(AeroRegistrate) new AeroRegistrate(Aeronautics.path("aeronautics"), MOD_ID).defaultCreativeTab((ResourceKey<CreativeModeTab>) null));

	public static void init() {
		setTooltips();
		getRegistrate().addDataGenerator(ProviderType.LANG, AeroLang::registrateLang);

		AeroBlocks.init();
		AeroBlockEntityTypes.init();
		AeroItems.init();
		AeroEntityTypes.init();
		AeroArmorMaterials.init();
		AeroSoundEvents.init();
		AeroLiftingGasTypes.init();
		AeroBlockMovementChecks.init();
		AeroRegistries.init();
		AeroPacketManager.init();
		AeroLevititeBlendPropagationContexts.init();
		AeroDataComponents.init();

		listenCommonEvents();
	}

	public static void setTooltips() {
		getRegistrate().setTooltipModifierFactory(item -> {
			final Rarity rarity = item.getDefaultInstance().getRarity();
			FontHelper.Palette color = FontHelper.Palette.STANDARD_CREATE;
			if (rarity == Rarity.EPIC)
				color = new FontHelper.Palette(TooltipHelper.styleFromColor(SimColors.EPIC_OURPLE), TooltipHelper.styleFromColor(rarity.color()));

			return new ItemDescription
					.Modifier(item, color)
					.andThen(TooltipModifier.mapNull(KineticStats.create(item)));
		});
	}

	private static void listenCommonEvents() {
		SableEventPlatform.INSTANCE.onPhysicsTick(AeronauticsCommonEvents::physicsTick);
		SableEventPlatform.INSTANCE.onSubLevelContainerReady(AeronauticsCommonEvents::onSubLevelContainerReady);
	}

	public static AeroRegistrate getRegistrate() {
		return REGISTRATE.get();
	}

	public static ResourceLocation path(final String path) {
		return ResourceLocation.tryBuild(MOD_ID, path);
	}
}
