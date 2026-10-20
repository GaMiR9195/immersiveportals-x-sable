package dev.eriksonn.aeronautics.index;

import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.simulated_team.simulated.registrate.SimulatedRegistrate;
import com.tterrag.registrate.util.entry.EntityEntry;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.content.blocks.hot_air.gust.GustEntity;
import dev.eriksonn.aeronautics.content.blocks.propeller.bearing.contraption.PropellerBearingContraptionEntity;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.MobCategory;

public class AeroEntityTypes {
	private static final SimulatedRegistrate REGISTRATE = Aeronautics.getRegistrate();

	public static final EntityEntry<PropellerBearingContraptionEntity> PROPELLER_CONTROLLED_CONTRAPTION =
			REGISTRATE.entity("propeller_bearing_contraption", PropellerBearingContraptionEntity::new, MobCategory.MISC)
					.visual(() -> ContraptionVisual::new)
					.renderer(() -> ContraptionEntityRenderer::new)
					.transform((builder) -> builder.properties(b -> b
										.clientTrackingRange(20)
										.updateInterval(40)
										.sized(1, 1)
										.eyeHeight(0)
										.fireImmune()))
					.register();

    public static final EntityEntry<GustEntity> GUST =
            REGISTRATE.<GustEntity>entity("gust", GustEntity::new, MobCategory.MISC)
                    .renderer(() -> NoopRenderer::new)
                    .transform((builder) -> builder.properties(b -> b
                            .clientTrackingRange(20)
                            .sized(1, 1)
                            .eyeHeight(0)
                            .fireImmune()))
                    .register();

	public static void init() {}
}
