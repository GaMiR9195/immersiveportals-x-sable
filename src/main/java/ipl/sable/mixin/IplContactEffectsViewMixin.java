package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
/** Collision reports already contain WORLD positions. Resolve their view from the report's bodies. */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class IplContactEffectsViewMixin {
    @Unique private static ServerLevel ipl$effectWorld(ServerLevel fallback, ServerSubLevel a, ServerSubLevel b) {
        ServerSubLevel owner = a != null ? a : b;
        if (owner == null || !IplDimAgnostic.isHosted(owner)) return fallback;
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(owner);
        return parent == null ? fallback : parent;
    }
    @WrapOperation(method = "processCollisionEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"), require = 1)
    private int ipl$particles(ServerLevel level, ParticleOptions options, double x,double y,double z,
        int count,double dx,double dy,double dz,double speed,Operation<Integer> original,
        @Local(ordinal = 0) ServerSubLevel a,@Local(ordinal = 1) ServerSubLevel b) {
        return original.call(ipl$effectWorld(level,a,b),options,x,y,z,count,dx,dy,dz,speed);
    }
    @WrapOperation(method = "processCollisionEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"), require = 3)
    private void ipl$sounds(ServerLevel level,Player except,double x,double y,double z,SoundEvent sound,
        SoundSource category,float volume,float pitch,Operation<Void> original,
        @Local(ordinal = 0) ServerSubLevel a,@Local(ordinal = 1) ServerSubLevel b) {
        original.call(ipl$effectWorld(level,a,b),except,x,y,z,sound,category,volume,pitch);
    }
}
