package ipl.sable.mixin;

import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block reads of a chunk that belongs to ANOTHER level (a bridged plot chunk, see
 * {@link SubLevelContainerMixin}) use that chunk's own section layout.
 *
 * <p>Русский: {@code LevelAccelerator.getBlockState(LevelChunk, BlockPos)} считает индекс
 * секции и границы высоты по СВОЕМУ уровню. Коллизия сущностей строит акселератор по
 * уровню сущности, а проекция корабля из другого измерения читает чанк плота уровня-
 * владельца: у Overworld minSection = -4, у Nether/End = 0 — читалась чужая секция (воздух),
 * поэтому на проекции в другом измерении нельзя было стоять, хотя рейкасты (они идут через
 * {@code LevelChunk.getBlockState}) и рендер работали.
 */
@Pseudo
@Mixin(value = LevelAccelerator.class, remap = false)
public abstract class LevelAcceleratorMixin {

    @Shadow
    @Final
    private Level level;

    @Inject(
        method = "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipl$readForeignChunk(LevelChunk chunk, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (chunk != null && chunk.getLevel() != this.level) cir.setReturnValue(chunk.getBlockState(pos));
    }
}
