package ipl.sable.mixin.client;
import ipl.sable.dim.IplClientWorldFrameContext;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Hosted ClientLevel owns blocks, not the visible world's particle renderer. */
@Mixin(ClientLevel.class)
public abstract class IplHostedClientEffectsMixin {
    @Unique private ClientLevel ipl$view(double x,double y,double z) {
        ClientLevel self=(ClientLevel)(Object)this;
        if (!IplDimAgnostic.isHostingLevel(self)) return null;
        ClientLevel parent=IplClientWorldFrameContext.resolveParentForPlotBe(self,BlockPos.containing(x,y,z));
        if (parent==null) parent=IplClientWorldFrameContext.current();
        return parent==self || IplDimAgnostic.isHostingLevel(parent) ? null : parent;
    }
    @Inject(method="addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",at=@At("HEAD"),cancellable=true,require=1)
    private void ipl$particle(ParticleOptions p,double x,double y,double z,double dx,double dy,double dz,CallbackInfo ci) {
        var parent=ipl$view(x,y,z); if(parent!=null){parent.addParticle(p,x,y,z,dx,dy,dz);ci.cancel();}
    }
    @Inject(method="addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V",at=@At("HEAD"),cancellable=true,require=1)
    private void ipl$forcedParticle(ParticleOptions p,boolean force,double x,double y,double z,double dx,double dy,double dz,CallbackInfo ci) {
        var parent=ipl$view(x,y,z); if(parent!=null){parent.addParticle(p,force,x,y,z,dx,dy,dz);ci.cancel();}
    }
    @Inject(method="addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",at=@At("HEAD"),cancellable=true,require=1)
    private void ipl$visibleParticle(ParticleOptions p,double x,double y,double z,double dx,double dy,double dz,CallbackInfo ci) {
        var parent=ipl$view(x,y,z); if(parent!=null){parent.addAlwaysVisibleParticle(p,x,y,z,dx,dy,dz);ci.cancel();}
    }
    @Inject(method="addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V",at=@At("HEAD"),cancellable=true,require=1)
    private void ipl$forcedVisibleParticle(ParticleOptions p,boolean force,double x,double y,double z,double dx,double dy,double dz,CallbackInfo ci) {
        var parent=ipl$view(x,y,z); if(parent!=null){parent.addAlwaysVisibleParticle(p,force,x,y,z,dx,dy,dz);ci.cancel();}
    }
    @Inject(method="playSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZJ)V",at=@At("HEAD"),cancellable=true,require=1)
    private void ipl$sound(double x,double y,double z,SoundEvent sound,SoundSource category,float volume,float pitch,boolean delay,long seed,CallbackInfo ci) {
        var parent=ipl$view(x,y,z); if(parent!=null){((IplClientSoundInvoker)parent).ipl$playSound(x,y,z,sound,category,volume,pitch,delay,seed);ci.cancel();}
    }
}
