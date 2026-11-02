package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.transit.IplEntityFrames;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entity position updates of an entity standing on a projection go out in world coordinates
 * (see {@link IplEntityFrames#prepareTrackedPosition}). Sable switches the position field to
 * ship-local coordinates right before this read and restores it at RETURN.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @WrapOperation(
        method = "sendChanges",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;trackingPosition()Lnet/minecraft/world/phys/Vec3;"),
        require = 1
    )
    private Vec3 ipl$sendInAttachedFrame(Entity entity, Operation<Vec3> original) {
        IplEntityFrames.prepareTrackedPosition(entity);
        return original.call(entity);
    }
}
