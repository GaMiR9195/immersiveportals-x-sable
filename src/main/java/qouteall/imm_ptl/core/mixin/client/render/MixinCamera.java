package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.FrameCameraInputs;
import qouteall.imm_ptl.core.render.PortalCameraClip;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(Camera.class)
public abstract class MixinCamera implements IECamera {
    @Shadow
    private Vec3 position;
    @Shadow
    private BlockGetter level;
    @Shadow
    private Entity entity;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;
    @Shadow
    private boolean initialized;
    @Shadow
    private boolean detached;
    @Shadow
    private float partialTickTime;
    
    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);
    
    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);
    
    @Shadow
    public abstract Entity getEntity();
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At("RETURN")
    )
    private void onUpdateFinished(
        BlockGetter area, Entity focusedEntity, boolean thirdPerson,
        boolean inverseView, float partialTick, CallbackInfo ci
    ) {
        Camera this_ = (Camera) (Object) this;
        WorldRenderInfo.adjustCameraPos(this_);
    }
    
    /**
     * Every camera, vanilla's or a mod's, collides with blocks through portals: {@link PortalCameraClip}.
     * The camera of a portal pass is not set up again: it takes the player's view of the frame
     * ({@link FrameCameraInputs#mirroredView}), so camera mods run once per frame and their state
     * (shoulder offsets, collision smoothing, hidden or faded own player) stays the player's.
     */
    @WrapMethod(method = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V")
    private void ip_portalAwareSetup(
        BlockGetter area, Entity focusedEntity, boolean thirdPerson,
        boolean inverseView, float partialTick, Operation<Void> original
    ) {
        Camera self = (Camera) (Object) this;
        Camera view = FrameCameraInputs.mirroredView(self);
        if (view != null) {
            ip_mirror(view, area, focusedEntity, partialTick);
            WorldRenderInfo.adjustCameraPos(self);
            return;
        }
        PortalCameraClip.setup(
            area, focusedEntity, () -> original.call(area, focusedEntity, thirdPerson, inverseView, partialTick)
        );
        FrameCameraInputs.onSetUp(self);
    }
    
    /** Camera mods' angles are evaluated by the player's view only, see {@link FrameCameraInputs}. */
    @WrapOperation(
        method = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;",
            remap = false
        )
    )
    private Event ip_shareCameraAngles(IEventBus bus, Event event, Operation<Event> original) {
        if (event instanceof ViewportEvent.ComputeCameraAngles angles) {
            FrameCameraInputs.computeAngles((Camera) (Object) this, angles, () -> original.call(bus, event));
            return event;
        }
        return original.call(bus, event);
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void getSubmergedFluidState(CallbackInfoReturnable<FogType> cir) {
        if (PortalRendering.isRendering()) {
            cir.setReturnValue(FogType.NONE);
            cir.cancel();
        }
    }
    
    // to let the player be rendered when rendering portal
    @Inject(method = "Lnet/minecraft/client/Camera;isDetached()Z", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (CrossPortalEntityRenderer.shouldRenderPlayerDefault()) {
            cir.setReturnValue(true);
        }
    }
    
    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }
    
    @Override
    public float ip_getCameraY() {
        return eyeHeight;
    }
    
    @Override
    public float ip_getLastCameraY() {
        return eyeHeightOld;
    }
    
    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }
    
    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }
    
    @Override
    public void portal_setFocusedEntity(Entity arg) {
        entity = arg;
    }
    
    @Override
    public void ip_mirror(Camera view, BlockGetter area, Entity focusedEntity, float partialTick) {
        IECamera source = (IECamera) view;
        initialized = true;
        level = area;
        entity = focusedEntity;
        detached = source.ip_wasSetUpDetached();
        partialTickTime = partialTick;
        eyeHeight = source.ip_getCameraY();
        eyeHeightOld = source.ip_getLastCameraY();
        setRotation(view.getYRot(), view.getXRot(), view.getRoll());
        setPosition(view.getPosition());
    }
    
    @Override
    public boolean ip_wasSetUpDetached() {
        return detached;
    }
}
