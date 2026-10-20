package net.irisshaders.iris.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.sodium.config.IrisConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(VideoSettingsScreen.class)
public class MixinSodiumGameOptions {
	@WrapMethod(method = "renderIconWithSpacing", require = 0)
	private static int iris$makeColor(GuiGraphics graphics, ResourceLocation icon, int color, boolean iconMonochrome, int x, int y, int height, int margin, Operation<Integer> original) {
		boolean newMonochrome = iconMonochrome;
		ResourceLocation newIdentifier = icon;

		if (icon.getNamespace().equals("iris")) {
			if (Iris.getCurrentPack().isPresent()) {
				newIdentifier = IrisConfig.COLOR;
				newMonochrome = false;
			}
		}

		return original.call(graphics, newIdentifier, color, newMonochrome, x, y, height, margin);
	}
}
