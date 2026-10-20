package dev.eriksonn.aeronautics.neoforge.index;

import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.simulated_team.simulated.registrate.SimulatedRegistrate;
import com.tterrag.registrate.util.entry.FluidEntry;
import dev.eriksonn.aeronautics.Aeronautics;
import dev.eriksonn.aeronautics.index.AeroSoundEvents;
import dev.eriksonn.aeronautics.neoforge.content.fluids.AeroFluidType;
import dev.eriksonn.aeronautics.neoforge.content.fluids.levitite.LevititeBlendFluidType;
import dev.eriksonn.aeronautics.neoforge.content.fluids.levitite.LevititeBlendNeoForge;
import dev.eriksonn.aeronautics.util.AeroColors;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;

public class AeroFluidsNeoForge {
	public static final SimulatedRegistrate REGISTRATE = Aeronautics.getRegistrate();

	public static final FluidEntry<BaseFlowingFluid.Flowing> LEVITITE_BLEND = REGISTRATE
			.standardFluid("levitite_blend", AeroFluidType.create(AeroColors.LEVIBLEND_THE_FOG_IS_COMING,
					() -> 1f / 32f * AllConfigs.client().chocolateTransparencyMultiplier.getF(),
					LevititeBlendFluidType::new))
			.lang("Levitite Blend")
			.properties(b -> b.viscosity(1500)
					.density(1400)
					.motionScale(-7 / 3000f)
					.canSwim(false))
			.properties(p -> p.sound(SoundActions.BUCKET_EMPTY, AeroSoundEvents.LEVITITE_BLEND_EMPTY.event()))
			.properties(p -> p.sound(SoundActions.BUCKET_FILL, AeroSoundEvents.LEVITITE_BLEND_FILL.event()))
			.fluidProperties(p -> p.levelDecreasePerBlock(2)
					.tickRate(25)
					.slopeFindDistance(3)
					.explosionResistance(100f))
			.source(LevititeBlendNeoForge::new)
			.bucket()
			.build()
			.register();

	public static void registerFluidInteractions() {
		FluidInteractionRegistry.InteractionInformation interaction = new FluidInteractionRegistry.InteractionInformation(LEVITITE_BLEND.getType(), (fluidState) ->
				fluidState.isSource() ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.CALCITE.defaultBlockState());

		FluidInteractionRegistry.addInteraction(NeoForgeMod.LAVA_TYPE.value(), interaction);
	}

	public static void init() {

	}
}
