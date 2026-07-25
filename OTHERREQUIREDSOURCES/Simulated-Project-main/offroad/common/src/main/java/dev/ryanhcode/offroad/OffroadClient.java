package dev.ryanhcode.offroad;

import dev.ryanhcode.offroad.content.ponder.OffroadPonderPlugin;
import dev.ryanhcode.offroad.index.OffroadPartialModels;
import net.createmod.ponder.foundation.PonderIndex;

public class OffroadClient {
	public static void init() {
		PonderIndex.addPlugin(new OffroadPonderPlugin());

		OffroadPartialModels.init();
	}
}
