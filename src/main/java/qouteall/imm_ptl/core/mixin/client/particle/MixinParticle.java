package qouteall.imm_ptl.core.mixin.client.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.ParticleTeleportation;

import java.util.List;

/** Particle side of {@link ParticleTeleportation}; optional, so a mod replacing {@code move} only loses it. */
@Mixin(Particle.class)
public abstract class MixinParticle {
    @Inject(method = "move", at = @At("HEAD"), require = 0)
    private void ip_onMove(double dx, double dy, double dz, CallbackInfo ci) {
        ParticleTeleportation.onMove((Particle) (Object) this);
    }

    // a particle flying into a portal on a wall must not stop at the wall behind it
    @WrapOperation(
        method = "move",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"
        ),
        require = 0
    )
    private Vec3 ip_passPortals(
        Entity entity, Vec3 motion, AABB box, Level level, List<VoxelShape> shapes, Operation<Vec3> original
    ) {
        if (ParticleTeleportation.passesPortal((Particle) (Object) this, motion)) {
            return motion;
        }
        return original.call(entity, motion, box, level, shapes);
    }
}
