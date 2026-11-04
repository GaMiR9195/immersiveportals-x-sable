package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.transit.IplProjectionEntities;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A projectile hitting a PROJECTED ship (the hit comes back in the ship's plot coordinates
 * from the portal-aware clip) triggers the block in the level that owns the ship: target
 * blocks, bells, buttons, chorus flowers, TNT lit by flaming arrows, decorated pots.
 *
 * <p>В уровне снаряда запись в чужой плот отклоняется, и попадание «проходило» бы, ничего не
 * включив.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @WrapOperation(
        method = "onHitBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onProjectileHit(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/entity/projectile/Projectile;)V"
        )
    )
    private void ipl$projectileHitInOwnerLevel(BlockState self, Level level, BlockState state, BlockHitResult hit,
                                               Projectile projectile, Operation<Void> original) {
        original.call(self, IplProjectionEntities.owningLevel(level, hit.getBlockPos()), state, hit, projectile);
    }
}
