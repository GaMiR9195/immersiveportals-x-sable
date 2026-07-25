package top.leonx.irisveil.compat.simulated;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SimulatedFirstPersonProjectionTest {

    @Test
    void keepsCapturedProjectionWhenShaderpackIsDisabled() {
        Matrix4f capturedProjection = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
        Matrix4f vanillaHandProjection = new Matrix4f().scaling(2.0f, 3.0f, 4.0f);

        Matrix4f projection = SimulatedFirstPersonItemCapture.chooseItemProjection(
            capturedProjection,
            vanillaHandProjection,
            false
        );

        assertMatrixEquals(capturedProjection, projection);
    }

    @Test
    void replacesIrisDepthScaledProjectionWhenShaderpackIsEnabled() {
        Matrix4f irisDepthScaledProjection = new Matrix4f().scaling(1.0f, 1.0f, 0.125f);
        Matrix4f vanillaHandProjection = new Matrix4f().scaling(1.0f, 1.0f, 1.0f);

        Matrix4f projection = SimulatedFirstPersonItemCapture.chooseItemProjection(
            irisDepthScaledProjection,
            vanillaHandProjection,
            true
        );

        assertMatrixEquals(vanillaHandProjection, projection);
    }

    @Test
    void keepsCapturedFocusPositionWhenShaderpackIsDisabled() {
        Vector3f capturedFocusPosition = new Vector3f(1.0f, 2.0f, 3.0f);
        Quaternionf cameraRotation = new Quaternionf().rotateY((float) Math.toRadians(90.0));

        Vector3f focusPosition = SimulatedFirstPersonItemCapture.chooseFocusPosition(
            capturedFocusPosition,
            cameraRotation,
            new Matrix4f(),
            false
        );

        assertVectorEquals(capturedFocusPosition, focusPosition);
    }

    @Test
    void restoresVanillaCameraRotationOnFocusPositionWhenShaderpackIsEnabled() {
        Vector3f capturedFocusPosition = new Vector3f(1.0f, 0.0f, 0.0f);
        Quaternionf cameraRotation = new Quaternionf().rotateY((float) Math.toRadians(90.0));
        Vector3f expectedFocusPosition = cameraRotation.transform(new Vector3f(capturedFocusPosition));

        Vector3f focusPosition = SimulatedFirstPersonItemCapture.chooseFocusPosition(
            capturedFocusPosition,
            cameraRotation,
            new Matrix4f(),
            true
        );

        assertVectorEquals(expectedFocusPosition, focusPosition);
    }

    @Test
    void restoresIrisHandModelViewBeforeCameraRotationWhenShaderpackIsEnabled() {
        Vector3f capturedFocusPosition = new Vector3f(1.0f, 2.0f, 3.0f);
        Matrix4f handModelView = new Matrix4f().translation(4.0f, 5.0f, 6.0f);
        Quaternionf cameraRotation = new Quaternionf().rotateY((float) Math.toRadians(90.0));
        Vector3f expectedFocusPosition = handModelView.transformPosition(new Vector3f(capturedFocusPosition));
        cameraRotation.transform(expectedFocusPosition);

        Vector3f focusPosition = SimulatedFirstPersonItemCapture.chooseFocusPosition(
            capturedFocusPosition,
            cameraRotation,
            handModelView,
            true
        );

        assertVectorEquals(expectedFocusPosition, focusPosition);
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                org.junit.jupiter.api.Assertions.assertEquals(
                    expected.get(column, row),
                    actual.get(column, row),
                    0.0001f
                );
            }
        }
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected.x(), actual.x(), 0.0001f);
        org.junit.jupiter.api.Assertions.assertEquals(expected.y(), actual.y(), 0.0001f);
        org.junit.jupiter.api.Assertions.assertEquals(expected.z(), actual.z(), 0.0001f);
    }
}
