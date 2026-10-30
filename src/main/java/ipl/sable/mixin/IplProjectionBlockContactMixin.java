package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.transit.IplProjectionEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block contact callbacks of entities touching a PROJECTED ship run in the level that owns
 * the ship, and only for blocks of the piece the entity really touches.
 *
 * <p>Sable's merged in-block scan ({@code entities_in_blocks.EntityMixin}, and Pocket's
 * replacement of it) reads ship blocks through the plot bridge but calls
 * {@code entityInside(entityLevel, plotPos, entity)}: a pressure plate on a cross-dimension
 * image then wrote into a level that does not own the plot (rejected by the write guard)
 * and scheduled its release tick where that chunk does not exist. Same for
 * {@code stepOn}/{@code fallOn} (redstone ore, magma, farmland, turtle eggs).
 *
 * <p>WILDCARD targets on purpose (priority 1200, after Sable's 1100): the in-block scan is
 * a merged mixin handler whose mangled name cannot be targeted; vanilla's own calls pass
 * world positions and fall straight through ({@code ctx} is returned unchanged).
 *
 * <p>Русский комментарий: пропуск вызова ({@code entityInsideLevel == null}) — это
 * блок, чья касаемая часть срезана порталом в кадре, который сканировала сущность
 * (например, игрок стоит за порталом "сзади" там, где родная поза ещё содержит
 * улетевшую в портал часть корабля).
 */
@Mixin(value = Entity.class, priority = 1200)
public abstract class IplProjectionBlockContactMixin {

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V"
        ),
        require = 0
    )
    private void ipl$entityInsideInOwnerLevel(BlockState state, Level level, BlockPos pos, Entity entity,
                                              Operation<Void> original) {
        Level target = IplProjectionEntities.entityInsideLevel(level, pos, entity);
        if (target != null) original.call(state, target, pos, entity);
    }

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"
        ),
        require = 0
    )
    private void ipl$stepOnInOwnerLevel(Block block, Level level, BlockPos pos, BlockState state, Entity entity,
                                        Operation<Void> original) {
        original.call(block, IplProjectionEntities.owningLevel(level, pos), pos, state, entity);
    }

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;F)V"
        ),
        require = 0
    )
    private void ipl$fallOnInOwnerLevel(Block block, Level level, BlockState state, BlockPos pos, Entity entity,
                                        float distance, Operation<Void> original) {
        original.call(block, IplProjectionEntities.owningLevel(level, pos), state, pos, entity, distance);
    }
}
