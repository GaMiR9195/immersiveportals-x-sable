package dev.ryanhcode.offroad;

import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.item.TooltipModifier;
import dev.simulated_team.simulated.registrate.SimulatedRegistrate;
import dev.simulated_team.simulated.util.SimColors;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.ryanhcode.offroad.data.OffroadLang;
import dev.ryanhcode.offroad.events.OffroadCommonEvents;
import dev.ryanhcode.offroad.index.*;
import dev.ryanhcode.offroad.network.OffroadPacketManager;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Offroad {
	public static final String MOD_ID = "offroad";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final NonNullSupplier<SimulatedRegistrate> REGISTRATE = NonNullSupplier.lazy(() ->
			(SimulatedRegistrate) new SimulatedRegistrate(Offroad.path(MOD_ID), MOD_ID).defaultCreativeTab((ResourceKey<CreativeModeTab>) null));

	public static void init() {
		setTooltips();
		getRegistrate().addDataGenerator(ProviderType.LANG, OffroadLang::registrateLang);

		OffroadBlocks.init();
		OffroadBlockEntityTypes.init();
		OffroadEntityTypes.init();
		OffroadDataComponents.init();
		OffroadItems.init();
		OffroadSoundEvents.init();
		OffroadPacketManager.init();

		OffroadContraptionTypes.init();

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
		SableEventPlatform.INSTANCE.onPhysicsTick(OffroadCommonEvents::physicsTick);
	}

	public static SimulatedRegistrate getRegistrate() {
		return REGISTRATE.get();
	}

	public static ResourceLocation path(final String path) {
		return ResourceLocation.tryBuild(MOD_ID, path);
	}
}
