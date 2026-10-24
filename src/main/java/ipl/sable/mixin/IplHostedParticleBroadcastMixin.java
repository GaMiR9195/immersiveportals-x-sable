package ipl.sable.mixin;

import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Send plot-space particle packets through their actual world's player set. */
@Mixin(ServerLevel.class)
public abstract class IplHostedParticleBroadcastMixin {
    @Unique
    private ServerLevel ipl$particleParent(double x, double y, double z) {
        ServerLevel self = (ServerLevel) (Object) this;
        ServerLevel parent = IplWorldFrameContext.resolveParentForPlotBe(
            self, BlockPos.containing(x, y, z));
        if (parent == null && ipl.sable.dim.IplDimAgnostic.isHostingLevel(self)) {
            parent = IplWorldFrameContext.current();
        }
        return parent == self ? null : parent;
    }

    @Inject(
        method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
        at = @At("HEAD"), cancellable = true, require = 1
    )
    private <T extends ParticleOptions> void ipl$broadcast(
        T options, double x, double y, double z, int count,
        double dx, double dy, double dz, double speed, CallbackInfoReturnable<Integer> cir
    ) {
        ServerLevel parent = ipl$particleParent(x, y, z);
        if (parent != null) cir.setReturnValue(parent.sendParticles(
            options, x, y, z, count, dx, dy, dz, speed));
    }

    @Inject(
        method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZDDDIDDDD)Z",
        at = @At("HEAD"), cancellable = true, require = 1
    )
    private <T extends ParticleOptions> void ipl$sendToPlayer(
        ServerPlayer player, T options, boolean force, double x, double y, double z, int count,
        double dx, double dy, double dz, double speed, CallbackInfoReturnable<Boolean> cir
    ) {
        ServerLevel parent = ipl$particleParent(x, y, z);
        if (parent != null) cir.setReturnValue(parent.sendParticles(
            player, options, force, x, y, z, count, dx, dy, dz, speed));
    }
}