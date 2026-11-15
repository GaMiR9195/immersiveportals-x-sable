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
 * <p>{@code LevelAccelerator.getBlockState(LevelChunk, BlockPos)} computes the section index
 * and height bounds from its OWN level. Entity collision builds the accelerator for the entity's
 * level, while a ship projected from another dimension reads the plot chunk of its owner level
 * (Overworld minSection = -4, Nether/End = 0): without this the wrong section (air) would be
 * read and the projection could not be stood on, although raycasts
 * ({@code LevelChunk.getBlockState}) and rendering see it.
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
        cancellable = true
    )
    private void ipl$readForeignChunk(LevelChunk chunk, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (chunk != null && chunk.getLevel() != this.level) cir.setReturnValue(chunk.getBlockState(pos));
    }
}
