package dev.eriksonn.aeronautics.mixin.flywheel_block_entity;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlywheelBlockEntity.class)
public abstract class FlywheelBlockEntityMixin extends SmartBlockEntity {
    public FlywheelBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
    @Inject(method = "read",at = @At("HEAD"))
    public void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci, @Local(argsOnly = true) LocalBooleanRef localClientPacket)
    {
        localClientPacket.set(localClientPacket.get() || isVirtual());
    }
}
