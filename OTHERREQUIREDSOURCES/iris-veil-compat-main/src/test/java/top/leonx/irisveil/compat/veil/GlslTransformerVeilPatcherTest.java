package top.leonx.irisveil.compat.veil;

import com.mojang.blaze3d.vertex.VertexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlslTransformerVeilPatcherTest {
    private static final String IRIS_VERTEX = """
        #version 330 compatibility
        out vec2 texCoord;

        void main() {
            gl_Position = ftransform();
            texCoord = gl_MultiTexCoord0.xy;
        }
        """;

    private static final String ROPE_VERTEX = """
        #version 150
        out float vertexDistance;
        out vec4 vertexColor;
        out vec3 lightmapColor;
        out vec2 texCoord0;
        out vec2 texCoord2;
        out vec3 normal;

        uniform mat4 ModelViewMat;
        uniform mat4 ProjMat;
        uniform mat3 NormalMat;

        in vec3 Position;
        in vec4 Color;
        in vec2 UV0;
        in ivec2 UV2;
        in vec3 Normal;

        float fog_distance(vec3 pos, int shape) {
            return length(pos);
        }

        vec2 minecraft_sample_lightmap_coords(ivec2 uv) {
            return vec2(1.0);
        }

        float block_brightness(vec3 value) {
            return 1.0;
        }

        void main() {
            vec3 pos = Position;
            gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
            vertexDistance = fog_distance(pos, 0);
            vertexColor = Color * block_brightness(Normal);
            lightmapColor = vec3(1.0);
            texCoord0 = UV0;
            texCoord2 = minecraft_sample_lightmap_coords(UV2);
            normal = NormalMat * Normal;
        }
        """;

    private static final String ROPE_VERTEX_WITH_CHUNK_OFFSET = ROPE_VERTEX.replace(
        "in vec3 Normal;",
        "in vec3 Normal;\nuniform vec3 ChunkOffset;");

    private static final String PHOTON_SHADOW_VERTEX = """
        #version 330 compatibility
        in vec3 at_midBlock;

        vec3 transform(mat4 m, vec3 pos) {
            return mat3(m) * pos + m[3].xyz;
        }

        void update_voxel_map() {
            vec3 model_pos = gl_Vertex.xyz + at_midBlock * (1.0 / 64.0);
            vec3 view_pos = transform(gl_ModelViewMatrix, model_pos);
        }

        void main() {
            gl_Position = ftransform();
            update_voxel_map();
        }
        """;

    private static final String PHOTON_REPROJECTING_SHADOW_VERTEX = """
        #version 330 compatibility

        uniform mat4 shadowModelViewInverse;
        uniform vec3 cameraPosition;

        vec3 transform(mat4 m, vec3 pos) {
            return mat3(m) * pos + m[3].xyz;
        }

        vec4 project(mat4 m, vec3 pos) {
            return m * vec4(pos, 1.0);
        }

        vec3 distort_shadow_space(vec3 shadow_clip_pos) {
            return shadow_clip_pos;
        }

        void main() {
            vec3 pos = transform(gl_ModelViewMatrix, gl_Vertex.xyz);
            pos = transform(shadowModelViewInverse, pos);
            pos = pos + cameraPosition;
            pos = pos - cameraPosition;
            vec3 shadow_clip_pos = project(gl_ProjectionMatrix, pos).xyz;
            shadow_clip_pos = distort_shadow_space(shadow_clip_pos);
            gl_Position = vec4(shadow_clip_pos, 1.0);
        }
        """;

    @Test
    void renamesRopeVaryingsToMatchFragmentBridge() {
        String patched = patchRopeVertex();

        assertAll(
            () -> assertTrue(patched.contains("out vec3 _veil_lightmapColor"), patched),
            () -> assertTrue(patched.contains("out vec2 _veil_texCoord2"), patched),
            () -> assertTrue(patched.contains("out vec3 _veil_normal"), patched),
            () -> assertTrue(patched.contains("_veil_lightmapColor = vec3(1.0"), patched),
            () -> assertTrue(patched.contains("_veil_texCoord2 = minecraft_sample_lightmap_coords"), patched),
            () -> assertTrue(patched.contains("_veil_normal = gl_NormalMatrix * gl_Normal"), patched),
            () -> assertFalse(patched.contains("out vec3 lightmapColor"), patched),
            () -> assertFalse(patched.contains("out vec2 texCoord2"), patched)
        );
    }

    @Test
    void routesIrisVertexThroughOriginalModelSpaceVertex() {
        String patched = patchRopeVertex();

        assertAll(
            () -> assertTrue(patched.contains("_veil_modelVertex = gl_Vertex;"), patched),
            () -> assertFalse(
                patched.contains("inverse(gl_ProjectionMatrix * gl_ModelViewMatrix) * gl_Position"),
                patched)
        );
    }

    @Test
    void preservesPhotonShadowAtMidBlockVectorWidth() {
        String patched = new GlslTransformerVeilPatcher().patch(
            PHOTON_SHADOW_VERTEX,
            ROPE_VERTEX,
            new VertexFormat(),
            "shadow_veil_simulated_spring_spring");

        assertAll(
            () -> assertTrue(patched.contains("_veil_modelVertex.xyz + vec3(0.0"), patched),
            () -> assertFalse(patched.contains("_veil_modelVertex.xyz + vec4(0.0"), patched)
        );
    }

    @Test
    void routesPhotonShadowReprojectionThroughShadowMatrices() {
        String patched = new GlslTransformerVeilPatcher().patch(
            PHOTON_REPROJECTING_SHADOW_VERTEX,
            ROPE_VERTEX_WITH_CHUNK_OFFSET,
            new VertexFormat(),
            "shadow_veil_simulated_rope_rope");

        assertAll(
            () -> assertTrue(patched.contains("transform(shadowModelView, _veil_modelVertex.xyz)"), patched),
            () -> assertTrue(patched.contains("project(shadowProjection, pos)"), patched),
            () -> assertFalse(patched.contains("transform(gl_ModelViewMatrix, _veil_modelVertex.xyz)"), patched),
            () -> assertFalse(patched.contains("project(gl_ProjectionMatrix, pos)"), patched)
        );
    }

    @Test
    void keepsPhotonShadowReprojectionBakedForSpringWithoutChunkOffset() {
        String patched = new GlslTransformerVeilPatcher().patch(
            PHOTON_REPROJECTING_SHADOW_VERTEX,
            ROPE_VERTEX,
            new VertexFormat(),
            "shadow_veil_simulated_spring_spring");

        assertAll(
            () -> assertTrue(patched.contains("transform(mat4(1.0"), patched),
            () -> assertTrue(patched.contains("project(shadowProjection, pos)"), patched),
            () -> assertFalse(patched.contains("transform(shadowModelView, _veil_modelVertex.xyz)"), patched),
            () -> assertFalse(patched.contains("project(gl_ProjectionMatrix, pos)"), patched)
        );
    }

    private static String patchRopeVertex() {
        return new GlslTransformerVeilPatcher().patch(
            IRIS_VERTEX,
            ROPE_VERTEX,
            new VertexFormat(),
            "simulated:rope/rope");
    }
}
