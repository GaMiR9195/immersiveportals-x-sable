package dev.simulated_team.simulated.neoforge.mixin.harvesters;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.create.harvester.HarvesterTicker;
import dev.simulated_team.simulated.content.blocks.auger_shaft.BlockHarvester;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HarvesterTicker.class)
public class SableHarvesterTickerMixin {

    @WrapMethod(method = "dropItem")
    private static void deferDrop(final Level level, ItemStack dropped, final BlockPos sable$selfPos, final Operation<Void> original) {
        final BlockEntity be = level.getBlockEntity(sable$selfPos);
        if (be instanceof final BlockHarvester bh) {
            dropped = bh.depositItemStack(sable$selfPos, dropped);
            if (!dropped.isEmpty()) {
                original.call(level, dropped, sable$selfPos);
            }
        }
    }

}
