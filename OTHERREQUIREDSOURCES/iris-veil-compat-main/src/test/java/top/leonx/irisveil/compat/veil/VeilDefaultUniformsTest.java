package top.leonx.irisveil.compat.veil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeilDefaultUniformsTest {

    @Test
    void uploadsBlockFaceBrightnessInVeilDirectionOrder() {
        RecordingUploader uploader = new RecordingUploader();

        boolean uploaded = VeilDefaultUniforms.uploadBlockFaceBrightness(
            new float[] { 0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f },
            uploader);

        assertTrue(uploaded);
        assertEquals("VeilBlockFaceBrightness[0]", uploader.uniformName);
        assertEquals(17, uploader.location);
        assertArrayEquals(new float[] { 0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f }, uploader.values);
    }

    private static final class RecordingUploader implements VeilDefaultUniforms.UniformUploader {
        private String uniformName;
        private int location;
        private float[] values;

        @Override
        public int getUniformLocation(String name) {
            this.uniformName = name;
            return 17;
        }

        @Override
        public void uploadFloatArray(int location, float[] values) {
            this.location = location;
            this.values = values.clone();
        }
    }
}
