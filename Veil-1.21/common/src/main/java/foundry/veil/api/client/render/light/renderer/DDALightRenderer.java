package foundry.veil.api.client.render.light.renderer;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.DDALightData;
import foundry.veil.api.client.render.light.data.LightData;
import foundry.veil.api.client.render.shader.program.TextureUniformAccess;
import foundry.veil.api.client.render.shader.program.UniformAccess;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3fc;

import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;

/**
 * Updates DDA uniforms for the light renderer.
 *
 * @since 3.3.0
 */
public interface DDALightRenderer<T extends LightData & DDALightData> extends LightTypeRenderer<T> {

    /**
     * Uploads new uniform data from the GPU voxel grid.
     *
     * @param voxelGridTexture The voxel grid texture to use
     * @param gridOrigin       The origin of the grid in world space
     */
    void uploadVoxelGridUniforms(int voxelGridTexture, Vector3fc gridOrigin);

    default boolean hasOccludedLights() {
        int visibleLights = this.getVisibleLights();
        if (visibleLights <= 0) {
            return false;
        }

        for (LightRenderHandle<T> light : this.getLights()) {
            if (light.getLightData().isOcclusionEnabled()) {
                return true;
            }
        }
        return false;
    }

    static void uploadVoxelGridUniforms(ResourceLocation shaderId, int voxelGridTexture, Vector3fc gridOrigin) {
        uploadVoxelGridUniforms(VeilRenderSystem.renderer().getShaderManager().getShader(shaderId), voxelGridTexture, gridOrigin);
    }

    static <T extends UniformAccess & TextureUniformAccess> void uploadVoxelGridUniforms(T program, int voxelGridTexture, Vector3fc gridOrigin) {
        if (program == null) {
            return;
        }
        program.setTexture("BlockGrid", GL_TEXTURE_3D, voxelGridTexture);
        program.getUniformSafe("GridOrigin").setVector(gridOrigin);
    }
}
