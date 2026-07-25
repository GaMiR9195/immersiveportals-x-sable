package foundry.veil.api.client.render.shader;

import com.mojang.serialization.Codec;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.util.EnumCodec;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import static org.lwjgl.opengl.GL40C.GL_TESS_CONTROL_SHADER;
import static org.lwjgl.opengl.GL40C.GL_TESS_EVALUATION_SHADER;

/**
 * Shader features allow a higher-level way of requesting GLSL functionality without having to manually enable extensions per-shader.
 *
 * @since 2.0.0
 */
public enum ShaderFeature {
    COMPUTE,
    SHADER_STORAGE,
    ATOMIC_COUNTER,
    BINDLESS_TEXTURE,
    /**
     * @since 2.1.0
     */
    CUBE_MAP_ARRAY,
    /**
     * @since 3.0.0
     */
    FLOAT64,
    /**
     * @since 3.0.0
     */
    INT64,
    /**
     * @since 3.0.0
     */
    VERTEX_ATTRIBUTE64,
    /**
     * @since 3.1.0
     */
    TESSELLATION;

    @ApiStatus.Internal
    public static final ShaderFeature[] FEATURES = values();
    public static final Codec<ShaderFeature> CODEC = EnumCodec.<ShaderFeature>builder("Shader Feature")
            .values(values())
            .build();

    private final String definitionName;

    ShaderFeature() {
        this.definitionName = "SHADER_FEATURE_" + this.name();
    }

    /**
     * @return Whether this feature is supported on this platform
     */
    public boolean isSupported() {
        return switch (this) {
            case COMPUTE -> VeilRenderSystem.computeSupported();
            case SHADER_STORAGE -> VeilRenderSystem.shaderStorageBufferSupported();
            case ATOMIC_COUNTER -> VeilRenderSystem.atomicCounterSupported();
            case BINDLESS_TEXTURE -> VeilRenderSystem.bindlessTextureSupported();
            case CUBE_MAP_ARRAY -> VeilRenderSystem.textureCubeMapArraySupported();
            case FLOAT64 -> VeilRenderSystem.gpuShaderFloat64BitSupported();
            case INT64 -> VeilRenderSystem.gpuShaderInt64BitSupported();
            case VERTEX_ATTRIBUTE64 -> VeilRenderSystem.vertexAttribute64BitSupported();
            case TESSELLATION -> VeilRenderSystem.tessellationSupported();
        };
    }

    /**
     * @return The shader definition name in GLSL code
     */
    public String getDefinitionName() {
        return this.definitionName;
    }

    /**
     * Modifies the specified shader source to add the required GLSL extensions.
     *
     * @param shaderType The type of shader to modify
     * @param tree       The tree to modify
     * @since 3.4.0
     */
    public void modifyShader(int shaderType, GlslTree tree) {
        final List<String> directives = tree.getDirectives();
        switch (this) {
            case COMPUTE -> directives.add("#extension GL_ARB_compute_shader : require");
            case SHADER_STORAGE -> directives.add("#extension GL_ARB_shader_storage_buffer_object : require");
            case ATOMIC_COUNTER -> directives.add("#extension GL_ARB_shader_atomic_counters : require");
            case BINDLESS_TEXTURE -> {
                directives.add("#extension GL_ARB_bindless_texture : require");
                directives.add("#extension GL_NV_gpu_shader5 : enable");
                directives.add("#extension GL_EXT_nonuniform_qualifier : enable");
            }
            case FLOAT64 -> directives.add("#extension GL_ARB_gpu_shader_fp64 : require");
            case INT64 -> directives.add("#extension GL_ARB_gpu_shader_int64 : require");
            case VERTEX_ATTRIBUTE64 -> directives.add("#extension GL_ARB_vertex_attrib_64bit : require");
            case TESSELLATION -> {
                if (shaderType == GL_TESS_CONTROL_SHADER || shaderType == GL_TESS_EVALUATION_SHADER) {
                    directives.add("#extension GL_ARB_tessellation_shader : require");
                }
            }
        }
    }
}
