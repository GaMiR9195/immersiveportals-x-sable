package top.leonx.irisveil.compat.veil;

import com.mojang.blaze3d.vertex.VertexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisVeilProgramLinkerTest {
    private static final String SHADOW_VERTEX = """
        #version 330 compatibility

        void main() {
            gl_Position = ftransform();
        }
        """;

    private static final String SHADOW_VERTEX_WITH_DISTORTION = """
        #version 330 compatibility

        uniform mat4 shadowModelView;
        uniform mat4 shadowModelViewInverse;
        uniform mat4 shadowProjection;
        uniform mat4 shadowProjectionInverse;
        out vec4 position;

        void main() {
            position = shadowModelViewInverse * shadowProjectionInverse * ftransform();
            gl_Position = shadowProjection * shadowModelView * position;
            float distortFactor = gl_Position.x * 0.1 + 1.0;
            gl_Position.xy *= 1.0 / distortFactor;
        }
        """;

    private static final String ROPE_VERTEX = """
        #version 150
        uniform mat4 ModelViewMat;
        uniform mat4 ProjMat;
        uniform vec3 ChunkOffset;

        in vec3 Position;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        }
        """;

    private static final String SPRING_VERTEX = """
        #version 150
        uniform mat4 ModelViewMat;
        uniform mat4 ProjMat;

        in vec3 Position;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        }
        """;

    @Test
    void shadowVertexProgramsUseVeilClipPositionInShadowMatrices() {
        String patched = IrisVeilProgramLinker.patchVertexSource(
            SHADOW_VERTEX,
            ROPE_VERTEX,
            new VertexFormat(),
            "shadow_veil_simulated_rope_rope",
            new GlslTransformerVeilPatcher());

        assertAll(
            () -> assertNotEquals(SHADOW_VERTEX, patched),
            () -> assertTrue(patched.contains("uniform mat4 shadowModelView;"), patched),
            () -> assertTrue(patched.contains("uniform mat4 shadowProjection;"), patched),
            () -> assertTrue(patched.contains("_veil_clipPosition = gl_Position;"), patched),
            () -> assertTrue(patched.contains("gl_Position = shadowProjection * shadowModelView * vec4(gl_Vertex.xyz"), patched),
            () -> assertTrue(patched.contains("gl_Position = _veil_clipPosition;"), patched),
            () -> assertFalse(patched.contains("gl_ProjectionMatrix * gl_ModelViewMatrix"), patched)
        );
    }

    @Test
    void shadowVertexProgramsKeepShaderpackFinalShadowTransform() {
        String patched = IrisVeilProgramLinker.patchVertexSource(
            SHADOW_VERTEX_WITH_DISTORTION,
            ROPE_VERTEX,
            new VertexFormat(),
            "shadow_veil_simulated_rope_rope",
            new GlslTransformerVeilPatcher());

        assertAll(
            () -> assertTrue(patched.contains("_veil_clipPosition = gl_Position;"), patched),
            () -> assertTrue(patched.contains("position = shadowModelViewInverse * shadowProjectionInverse * _veil_clipPosition"), patched),
            () -> assertTrue(
                patched.lastIndexOf("gl_Position = _veil_clipPosition;") < patched.indexOf("gl_Position.xy"),
                patched)
        );
    }

    @Test
    void shadowVertexProgramsAvoidDoubleModelViewForPoseStackBakedVertices() {
        String patched = IrisVeilProgramLinker.patchVertexSource(
            SHADOW_VERTEX_WITH_DISTORTION,
            SPRING_VERTEX,
            new VertexFormat(),
            "shadow_veil_simulated_spring_spring",
            new GlslTransformerVeilPatcher());

        assertAll(
            () -> assertTrue(patched.contains("gl_Position = shadowProjection * mat4(1.0f) * vec4(gl_Vertex.xyz"), patched),
            () -> assertFalse(patched.contains("gl_Position = shadowProjection * shadowModelView * vec4(gl_Vertex.xyz"), patched),
            () -> assertTrue(patched.contains("gl_Position.xy *= 1.0f / distortFactor;"), patched)
        );
    }
}
