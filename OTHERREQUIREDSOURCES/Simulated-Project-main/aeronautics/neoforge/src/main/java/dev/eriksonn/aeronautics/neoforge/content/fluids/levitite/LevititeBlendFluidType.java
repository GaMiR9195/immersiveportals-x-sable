package dev.eriksonn.aeronautics.neoforge.content.fluids.levitite;

import dev.eriksonn.aeronautics.neoforge.content.fluids.AeroFluidType;
import dev.eriksonn.aeronautics.neoforge.index.AeroFluidsNeoForge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

public class LevititeBlendFluidType extends AeroFluidType {
	public LevititeBlendFluidType(Properties properties, ResourceLocation stillTexture, ResourceLocation flowingTexture) {
		super(properties, stillTexture, flowingTexture);
	}

	@Override
	public boolean move(FluidState state, LivingEntity entity, Vec3 movementVector, double gravity) {
		// this is effectively the lava movement code
		final double originalY = entity.getY();
		final boolean falling = entity.getDeltaMovement().y < 0;
		final double fluidHeight = entity.getFluidTypeHeight(AeroFluidsNeoForge.LEVITITE_BLEND.getType());
		if (!entity.isCrouching()) { // if not crouching, reduce effective gravity based on immersion in fluid down to 0
			gravity = Math.clamp(gravity * (1f - fluidHeight),
					0, gravity);
		}
		entity.moveRelative(0.02F, movementVector);
		entity.move(MoverType.SELF, entity.getDeltaMovement());
		if (fluidHeight <= entity.getFluidJumpThreshold()) {
			entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.5, 0.7F, 0.5));
			entity.setDeltaMovement(entity.getFluidFallingAdjustedMovement(gravity, falling, entity.getDeltaMovement()));
		} else {
			entity.setDeltaMovement(entity.getDeltaMovement().scale(0.5));
		}

		if (gravity != 0.0) {
			entity.setDeltaMovement(entity.getDeltaMovement().add(0.0, -gravity / 4.0, 0.0));
		}

		final Vec3 interV = entity.getDeltaMovement();
		if (entity.horizontalCollision && entity.isFree(interV.x, interV.y + 0.6F - entity.getY() + originalY, interV.z)) {
			entity.setDeltaMovement(interV.x, 0.3F, interV.z);
		}
		return true;
	}
}
