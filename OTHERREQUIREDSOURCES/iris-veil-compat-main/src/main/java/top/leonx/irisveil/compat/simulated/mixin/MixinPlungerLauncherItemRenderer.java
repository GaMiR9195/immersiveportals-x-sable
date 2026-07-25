package top.leonx.irisveil.compat.simulated.mixin;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.leonx.irisveil.compat.simulated.SimulatedFirstPersonItemCapture;

@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.items.plunger_launcher.PlungerLauncherItemRenderer",
    remap = false
)
public abstract class MixinPlungerLauncherItemRenderer {

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Matrix4f;set(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"
        ),
        require = 0
    )
    private Matrix4f irisveil$captureVanillaProjection(Matrix4f itemProjMat, Matrix4fc capturedProjection) {
        return itemProjMat.set(SimulatedFirstPersonItemCapture.captureItemProjection());
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Vector3d;set(DDD)Lorg/joml/Vector3d;"
        ),
        require = 0
    )
    private Vector3d irisveil$captureVanillaFocusPosition(Vector3d focusPos, double x, double y, double z) {
        Vector3f focusPosition = SimulatedFirstPersonItemCapture.captureFocusPosition(
            new Vector3f((float) x, (float) y, (float) z)
        );
        return focusPos.set(focusPosition.x(), focusPosition.y(), focusPosition.z());
    }
}
