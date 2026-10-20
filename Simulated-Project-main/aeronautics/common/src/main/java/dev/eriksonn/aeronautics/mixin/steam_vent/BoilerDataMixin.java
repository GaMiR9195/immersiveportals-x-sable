package dev.eriksonn.aeronautics.mixin.steam_vent;


import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;
import dev.eriksonn.aeronautics.data.AeroLang;
import dev.eriksonn.aeronautics.index.AeroBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(BoilerData.class)
public abstract class BoilerDataMixin {
	@Shadow
	public int attachedEngines;

	@Shadow
	public abstract float getEngineEfficiency(int boilerSize);

	@Shadow
	public abstract boolean isPassive(int boilerSize);

	@Shadow
	@Final
	private static float passiveEngineEfficiency;

	@Unique
	private int aeronautics$attachedVents = 0;

	@Inject(method = "evaluate", at = @At("HEAD"))
	private void aeronautics$countVents1(final FluidTankBlockEntity controller, final CallbackInfoReturnable<Boolean> cir, @Share("prevVents") final LocalIntRef prevVents) {
		prevVents.set(this.aeronautics$attachedVents);
		this.aeronautics$attachedVents = 0;
	}

	@Inject(method = "evaluate", at = @At(value = "INVOKE", target = "Lcom/tterrag/registrate/util/entry/BlockEntry;has(Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 0))
	private void aeronautics$countVents2(final FluidTankBlockEntity controller, final CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 1) final BlockState attachedState) {
		if (AeroBlocks.STEAM_VENT.has(attachedState)) {
			this.aeronautics$attachedVents++;
		}
	}

	@ModifyReturnValue(method = "evaluate", at = @At("RETURN"))
	private boolean aeronautics$countVents3(final boolean original, @Share("prevVents") final LocalIntRef prevVents) {
		return original || this.aeronautics$attachedVents != prevVents.get();
	}

	@ModifyReturnValue(method = "isActive", at = @At("RETURN"))
	private boolean aeronautics$activeWithVents(final boolean original) {
		return original || this.aeronautics$attachedVents > 0;
	}

	@ModifyExpressionValue(method = {"getEngineEfficiency", "updateOcclusion"}, at = @At(value = "FIELD", target = "Lcom/simibubi/create/content/fluids/tank/BoilerData;attachedEngines:I"))
	private int aeronautics$ventEfficiency(final int original) {
		return original + this.aeronautics$attachedVents;
	}

	@ModifyExpressionValue(method = "addToGoggleTooltip", at = {
			@At(value = "FIELD", target = "Lcom/simibubi/create/content/fluids/tank/BoilerData;attachedEngines:I", ordinal = 0),
			@At(value = "FIELD", target = "Lcom/simibubi/create/content/fluids/tank/BoilerData;attachedEngines:I", ordinal = 2)
	})
	private int aeronautics$countSteamConsumer1(final int original) {
		return original + this.aeronautics$attachedVents;
	}

	/*
	double totalSU = getEngineEfficiency(boilerSize) * 16 * Math.max(boilerLevel, attachedEngines)
			* BlockStressValues.getCapacity(AllBlocks.STEAM_ENGINE.get());
		->
	double totalSU = (1 or 1/8) * 16 * Math.max(boilerLevel, 1) * BlockStressValues.getCapacity(AllBlocks.STEAM_ENGINE.get())
	 */
	@Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/tank/BoilerData;getEngineEfficiency(I)F"))
	private float aeronautics$trueMaxSU1(final BoilerData instance, final int boilerSize) {
		return this.isPassive(boilerSize) ? passiveEngineEfficiency : 1;
	}

	@Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
	private int aeronautics$trueMaxSU2(final int boilerLevel, final int attachedEngines) {
		return Math.max(boilerLevel, 1);
	}

	/*
	Kinetic Stress Capacity:
		[number]su
	Kinetic Stress Used:
		[number]su via n engines
		[number]su via n vents
	 */
	@Inject(method = "addToGoggleTooltip", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/lang/LangBuilder;forGoggles(Ljava/util/List;)V", shift = At.Shift.AFTER, ordinal = 2), cancellable = true)
	private void aeronautics$reformatBoilerTooltip(final List<Component> tooltip, final boolean isPlayerSneaking, final int boilerSize, final CallbackInfoReturnable<Boolean> cir, @Local final double totalSU, @Local(ordinal = 1) int boilerLevel) {
		CreateLang.number(totalSU)
				.translate("generic.unit.stress")
				.style(ChatFormatting.AQUA)
				.forGoggles(tooltip, 1);

		boilerLevel = Math.max(boilerLevel, 1);
		final float efficiency = this.isPassive(boilerSize) ? this.getEngineEfficiency(boilerSize) / passiveEngineEfficiency : this.getEngineEfficiency(boilerSize);
		final double engineSU = totalSU * efficiency * this.attachedEngines / boilerLevel;
		final double ventSU = totalSU * efficiency * this.aeronautics$attachedVents / boilerLevel;

		if (engineSU > 0 || ventSU > 0) {
			AeroLang.translate("tooltip.capacity_used").style(ChatFormatting.GRAY).forGoggles(tooltip);
		}
		if (engineSU > 0) {
			CreateLang.number(engineSU)
					.translate("generic.unit.stress")
					.style(ChatFormatting.AQUA)
					.space()
					.add((this.attachedEngines == 1 ? CreateLang.translate("boiler.via_one_engine")
							: CreateLang.translate("boiler.via_engines", this.attachedEngines)).style(ChatFormatting.DARK_GRAY))
					.forGoggles(tooltip, 1);
		}
		if (ventSU > 0) {
			CreateLang.number(ventSU)
					.translate("generic.unit.stress")
					.style(ChatFormatting.AQUA)
					.space()
					.add((this.aeronautics$attachedVents == 1 ? AeroLang.translate("boiler.via_one_vent")
							: AeroLang.translate("boiler.via_vents", this.aeronautics$attachedVents)).style(ChatFormatting.DARK_GRAY))
					.forGoggles(tooltip, 1);
		}
		cir.setReturnValue(true); // cancels
	}

	@Inject(method = "clear", at = @At("TAIL"))
	private void aeronautics$clearVents(final CallbackInfo ci) {
		this.aeronautics$attachedVents = 0;
	}

	@Inject(method = "write", at = @At("TAIL"))
	private void aeronautics$writeVentData(final CallbackInfoReturnable<CompoundTag> cir, @Local final CompoundTag nbt) {
		nbt.putInt("SimVents", this.aeronautics$attachedVents);
	}

	@Inject(method = "read", at = @At("TAIL"))
	private void aeronautics$readVentData(final CompoundTag nbt, final int boilerSize, final CallbackInfo ci) {
		this.aeronautics$attachedVents = nbt.getInt("SimVents");
	}
}
