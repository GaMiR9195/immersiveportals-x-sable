package top.leonx.irisveil.compat.veil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.Direction;
import org.lwjgl.opengl.GL20C;

public final class VeilDefaultUniforms {
    private static final String BLOCK_FACE_BRIGHTNESS_UNIFORM = "VeilBlockFaceBrightness[0]";
    private static final int DIRECTION_COUNT = 6;

    private VeilDefaultUniforms() {
    }

    public static void uploadBlockFaceBrightness(ShaderInstance shader) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        Direction[] directions = buildDirectionLookup();
        float[] values = new float[directions.length];
        for (int i = 0; i < directions.length; i++) {
            values[i] = level.getShade(directions[i], true);
        }

        uploadBlockFaceBrightness(values, new UniformUploader() {
            @Override
            public int getUniformLocation(String name) {
                return GL20C.glGetUniformLocation(shader.getId(), name);
            }

            @Override
            public void uploadFloatArray(int location, float[] values) {
                GL20C.glUniform1fv(location, values);
            }
        });
    }

    static boolean uploadBlockFaceBrightness(float[] brightness, UniformUploader uploader) {
        if (brightness.length != DIRECTION_COUNT) {
            throw new IllegalArgumentException("Expected 6 block face brightness values");
        }

        int location = uploader.getUniformLocation(BLOCK_FACE_BRIGHTNESS_UNIFORM);
        if (location < 0) {
            return false;
        }

        uploader.uploadFloatArray(location, brightness);
        return true;
    }

    private static Direction[] buildDirectionLookup() {
        Direction[] directions = new Direction[6];
        for (Direction direction : Direction.values()) {
            directions[direction.get3DDataValue()] = direction;
        }
        return directions;
    }

    interface UniformUploader {
        int getUniformLocation(String name);

        void uploadFloatArray(int location, float[] values);
    }
}
