package qouteall.imm_ptl.core.mixin.client.particle;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.teleportation.ParticleTeleportation;

@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;
    
    // skip particle rendering for far portals
    @Inject(
        method = "render",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBeginRenderParticles(
        LightTexture lightTexture, Camera camera, float f, CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            if (RenderStates.getRenderedPortalNum() > 4) {
                ci.cancel();
            }
        }
    }
    
    // maybe incompatible with sodium and iris
    @WrapWithCondition(
        method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
        )
    )
    private boolean redirectBuildGeometry(
        Particle instance, VertexConsumer vertexConsumer, Camera camera, float v
    ) {
        ParticleTeleportation.renderAtOpening(instance, vertexConsumer, camera, v);
        return RenderStates.shouldRenderParticle(instance);
    }
    
    // a particle at an opening is also drawn beyond it, away from where its own box is
    @WrapOperation(
        method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;getRenderBoundingBox(F)Lnet/minecraft/world/phys/AABB;"
        ),
        require = 0
    )
    private AABB ip_keepAtOpening(Particle particle, float partialTick, Operation<AABB> original) {
        return ParticleTeleportation.isAtOpening(particle) ? AABB.INFINITE : original.call(particle, partialTick);
    }
    
    // a lava ember particle can generate a smoke particle during ticking
    // avoid generating the particle into the wrong dimension
    @Inject(method = "Lnet/minecraft/client/particle/ParticleEngine;tickParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void onTickParticle(Particle particle, CallbackInfo ci) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            ci.cancel();
        }
    }
    
    @Inject(method = "tick", at = @At("HEAD"))
    private void onBeginTick(CallbackInfo ci) {
        ParticleTeleportation.beginTick(level);
    }
    
    // particles go through portals
    @WrapOperation(
        method = "tickParticle",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;tick()V")
    )
    private void wrapTickParticle(Particle particle, Operation<Void> original) {
        ParticleTeleportation.tick(particle, () -> original.call(particle));
    }
    
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
}
