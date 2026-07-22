package top.leonx.irisveil.compat.simulated;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.GameRendererAccessor;

public final class SimulatedFirstPersonItemCapture {

    private SimulatedFirstPersonItemCapture() {
    }

    public static Matrix4f captureItemProjection() {
        Matrix4f capturedProjection = RenderSystem.getProjectionMatrix();
        if (!IrisVeilCompat.isShaderPackInUse()) {
            return new Matrix4f(capturedProjection);
        }

        Matrix4f vanillaHandProjection = createVanillaHandProjection();
        return chooseItemProjection(capturedProjection, vanillaHandProjection, true);
    }

    public static Vector3f captureFocusPosition(Vector3fc capturedFocusPosition) {
        return chooseFocusPosition(
            capturedFocusPosition,
            Minecraft.getInstance().gameRenderer.getMainCamera().rotation(),
            RenderSystem.getModelViewMatrix(),
            IrisVeilCompat.isShaderPackInUse()
        );
    }

    static Matrix4f chooseItemProjection(
            Matrix4fc capturedProjection,
            Matrix4fc vanillaHandProjection,
            boolean shaderPackInUse) {
        if (!shaderPackInUse) {
            return new Matrix4f(capturedProjection);
        }

        return new Matrix4f(vanillaHandProjection);
    }

    static Vector3f chooseFocusPosition(
            Vector3fc capturedFocusPosition,
            Quaternionfc cameraRotation,
            Matrix4fc handModelView,
            boolean shaderPackInUse) {
        Vector3f focusPosition = new Vector3f(capturedFocusPosition);
        if (!shaderPackInUse) {
            return focusPosition;
        }

        handModelView.transformPosition(focusPosition);
        return cameraRotation.transform(focusPosition);
    }

    private static Matrix4f createVanillaHandProjection() {
        Minecraft minecraft = Minecraft.getInstance();
        GameRenderer gameRenderer = minecraft.gameRenderer;
        Camera camera = gameRenderer.getMainCamera();
        float partialTicks = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        double fov = ((GameRendererAccessor) gameRenderer).irisveil$invokeGetFov(camera, partialTicks, false);
        return gameRenderer.getProjectionMatrix(fov);
    }
}
