package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.IplClientWorldFrameContext;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A particle's lifecycle belongs to the emitting ship's parent world. Keep plot
 * coordinates and velocity unchanged here: Sable's initialKickOut owns that
 * transform and ship-velocity inheritance. Reprojecting at both seams is wrong.
 */
@Mixin(ParticleEngine.class)
public abstract class IplHostedParticleOwnerMixin {
    @WrapOperation(
        method = "makeParticle",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleProvider;createParticle(Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDD)Lnet/minecraft/client/particle/Particle;"),
        require = 1
    )
    private <T extends ParticleOptions> Particle ipl$particleOwner(
        ParticleProvider<T> provider, T options, ClientLevel source,
        double x, double y, double z, double dx, double dy, double dz,
        Operation<Particle> original
    ) {
        ClientLevel parent = IplClientWorldFrameContext.resolveParentForPlotBe(
            source, BlockPos.containing(x, y, z));
        if (parent == null && IplDimAgnostic.isHostingLevel(source)) {
            parent = IplClientWorldFrameContext.current();
        }
        return original.call(provider, options, parent == null ? source : parent,
            x, y, z, dx, dy, dz);
    }
}