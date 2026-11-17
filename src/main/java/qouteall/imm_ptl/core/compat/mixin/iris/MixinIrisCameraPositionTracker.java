package qouteall.imm_ptl.core.compat.mixin.iris;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisViewContexts;

/** cameraPosition / previousCameraPosition of each view follow that view's own camera. */
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
public class MixinIrisCameraPositionTracker implements IrisViewContexts.Temporal {
    @Shadow private Vector3d previousCameraPosition;
    @Shadow private Vector3d currentCameraPosition;
    @Shadow private Vector3d previousCameraPositionUnshifted;
    @Shadow private Vector3d currentCameraPositionUnshifted;
    @Shadow @Final private Vector3d shift;

    @Unique private final IrisViewContexts.Binding ip_binding = new IrisViewContexts.Binding();
    @Unique private boolean ip_fresh;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void ip_onUpdate(CallbackInfo ci) {
        if (!ip_binding.bindAndAdvance(this)) ci.cancel();
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void ip_onUpdated(CallbackInfo ci) {
        if (ip_fresh) {
            // The first frame of a view has no motion.
            ip_fresh = false;
            previousCameraPosition = new Vector3d(currentCameraPosition);
            previousCameraPositionUnshifted = new Vector3d(currentCameraPositionUnshifted);
        }
    }

    @Inject(
        method = {"getCurrentCameraPosition", "getPreviousCameraPosition", "getPreviousCameraPositionUnshifted"},
        at = @At("HEAD")
    )
    private void ip_onReadPosition(CallbackInfoReturnable<Vector3d> cir) {
        ip_binding.bind(this);
    }

    @Inject(method = "getCurrentCameraPositionY", at = @At("HEAD"))
    private void ip_onReadY(CallbackInfoReturnable<Double> cir) {
        ip_binding.bind(this);
    }

    @Override
    public Object ip_captureTemporal() {
        return new Vector3d[]{
            new Vector3d(previousCameraPosition), new Vector3d(currentCameraPosition),
            new Vector3d(previousCameraPositionUnshifted), new Vector3d(currentCameraPositionUnshifted),
            new Vector3d(shift)
        };
    }

    @Override
    public void ip_restoreTemporal(Object state) {
        if (state instanceof Vector3d[] s) {
            previousCameraPosition = new Vector3d(s[0]);
            currentCameraPosition = new Vector3d(s[1]);
            previousCameraPositionUnshifted = new Vector3d(s[2]);
            currentCameraPositionUnshifted = new Vector3d(s[3]);
            shift.set(s[4]);
            ip_fresh = false;
        }
        else {
            previousCameraPosition = new Vector3d();
            currentCameraPosition = new Vector3d();
            previousCameraPositionUnshifted = new Vector3d();
            currentCameraPositionUnshifted = new Vector3d();
            shift.set(0, 0, 0);
            ip_fresh = true;
        }
    }
}
