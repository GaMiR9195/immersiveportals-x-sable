package top.leonx.irisveil.compat.veil;

import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import top.leonx.irisveil.IrisVeilCompat;

import java.util.List;

/**
 * Patches Iris shaderpack vertex and fragment shaders with screen-door dithering
 * for translucent Veil materials when the shaderpack lacks
 * {@code gbuffers_entities_translucent}.
 *
 * <p>Carries an alpha varying ({@code _veil_alpha}) from vertex to fragment stage
 * and performs ordered dithering using Bayer (8x8 matrix) or IGN
 * (Interleaved Gradient Noise). Follows the same glsl-transformer injection
 * pattern as GhostBlockShaderPatcher.</p>
 */
public final class VeilDitheringPatcher {

    private VeilDitheringPatcher() {}

    /** Result of dithering patch: modified shader sources. */
    public record DitherResult(String patchedVertex, String patchedFragment) {}

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Applies screen-door dithering to vertex and fragment sources.
     *
     * @param vertexSource        original vertex shader source
     * @param fragmentSource      original fragment shader source
     * @param hasVeilVertexColor  if true, reads alpha from {@code _veil_vertexColor.a};
     *                            otherwise uses {@code gl_Color.a}
     * @param method              "BAYER" or "IGN"
     * @return patched sources, or originals if patching failed
     */
    public static DitherResult applyDithering(String vertexSource,
                                               String fragmentSource,
                                               boolean hasVeilVertexColor,
                                               String method) {
        String colorSource = hasVeilVertexColor ? "_veil_vertexColor.a" : "gl_Color.a";
        String patchedVert = VertexPatcher.patch(vertexSource, colorSource);
        String patchedFrag = FragmentPatcher.patch(fragmentSource, method, "_veil_alpha");
        return new DitherResult(patchedVert, patchedFrag);
    }

    /**
     * Applies screen-door dithering using an alpha value computed in the fragment
     * shader. This is required for Veil shaders such as lasers where alpha is
     * derived from fragment varyings rather than vertex color alone.
     */
    public static DitherResult applyFragmentDithering(String vertexSource,
                                                       String fragmentSource,
                                                       String method) {
        String patchedFrag = FragmentPatcher.patch(fragmentSource, method, "_veil_fragColor.a");
        return new DitherResult(vertexSource, patchedFrag);
    }

    // ── Shared types ─────────────────────────────────────────────────────

    private static final class ParseParams implements JobParameters {}

    // ══════════════════════════════════════════════════════════════════════
    // VertexPatcher
    // ══════════════════════════════════════════════════════════════════════

    public static final class VertexPatcher {

        private VertexPatcher() {}

        /**
         * Injects {@code varying float _veil_alpha;} and assigns it from {@code colorSource}
         * at the end of the vertex main body.
         *
         * @param vertexSource original vertex shader
         * @param colorSource  GLSL expression yielding the alpha value,
         *                     e.g. "gl_Color.a" or "_veil_vertexColor.a"
         * @return patched vertex source
         */
        public static String patch(String vertexSource, String colorSource) {
            try {
                final SingleASTTransformer<ParseParams> t;
                t = new SingleASTTransformer<>() {{
                    setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
                }};
                t.setTransformation((tree, root, params) -> {
                    tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            "varying float _veil_alpha;");
                    tree.appendMainFunctionBody(t,
                            "_veil_alpha = " + colorSource + ";");
                });
                IrisVeilCompat.LOGGER.debug("VeilDithering VertexPatcher: injected _veil_alpha = {}", colorSource);
                return t.transform(vertexSource, new ParseParams());
            } catch (Exception e) {
                IrisVeilCompat.LOGGER.warn("VeilDithering VertexPatcher: AST injection failed", e);
                return vertexSource;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // FragmentPatcher
    // ══════════════════════════════════════════════════════════════════════

    public static final class FragmentPatcher {

        private FragmentPatcher() {}

        /** Bayer 8x8 matrix. */
        private static final String BAYER_MATRIX = """
            const float _veil_bayer[64] = float[64](
                0.0/64.0, 32.0/64.0,  8.0/64.0, 40.0/64.0,  2.0/64.0, 34.0/64.0, 10.0/64.0, 42.0/64.0,
                48.0/64.0, 16.0/64.0, 56.0/64.0, 24.0/64.0, 50.0/64.0, 18.0/64.0, 58.0/64.0, 26.0/64.0,
                12.0/64.0, 44.0/64.0,  4.0/64.0, 36.0/64.0, 14.0/64.0, 46.0/64.0,  6.0/64.0, 38.0/64.0,
                60.0/64.0, 28.0/64.0, 52.0/64.0, 20.0/64.0, 62.0/64.0, 30.0/64.0, 54.0/64.0, 22.0/64.0,
                3.0/64.0, 35.0/64.0, 11.0/64.0, 43.0/64.0,  1.0/64.0, 33.0/64.0,  9.0/64.0, 41.0/64.0,
                51.0/64.0, 19.0/64.0, 59.0/64.0, 27.0/64.0, 49.0/64.0, 17.0/64.0, 57.0/64.0, 25.0/64.0,
                15.0/64.0, 47.0/64.0,  7.0/64.0, 39.0/64.0, 13.0/64.0, 45.0/64.0,  5.0/64.0, 37.0/64.0,
                63.0/64.0, 31.0/64.0, 55.0/64.0, 23.0/64.0, 61.0/64.0, 29.0/64.0, 53.0/64.0, 21.0/64.0
            );""";

        /** Interleaved Gradient Noise with temporal dithering.
         *  {@code frameTimeCounter} is Iris's accumulated time uniform (seconds),
         *  available in all shaderpack programs. The golden ratio (φ⁻¹)
         *  creates a well-distributed temporal sequence — each pixel gets a
         *  different noise threshold each frame, smoothing out the dither
         *  pattern when the scene is static. */
        private static final String IGN_FUNC = """
            float _veil_noise(vec2 p) {
                return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)) + frameTimeCounter));
            }""";

        /**
         * Injects dithering logic into the fragment shader.
         *
         * @param fragmentSource original fragment shader
         * @param method         "BAYER" or "IGN"
         * @return patched fragment source
         */
        public static String patch(String fragmentSource, String method, String alphaSource) {
            boolean isBayer = "BAYER".equalsIgnoreCase(method);
            try {
                final SingleASTTransformer<ParseParams> t;
                t = new SingleASTTransformer<>() {{
                    setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
                }};
                if (isBayer) {
                    t.setTransformation((tree, root, params) -> {
                        if ("_veil_alpha".equals(alphaSource)) {
                            tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                                    "varying float _veil_alpha;");
                        }
                        tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                                BAYER_MATRIX);
                        tree.appendMainFunctionBody(t,
                                "int _veil_bx = int(mod(gl_FragCoord.x, 8.0));",
                                "int _veil_by = int(mod(gl_FragCoord.y, 8.0));",
                                "if (" + alphaSource + " <= _veil_bayer[_veil_by * 8 + _veil_bx]) discard;");
                    });
                } else {
                    t.setTransformation((tree, root, params) -> {
                        var mainDef = tree.getOneMainDefinitionBody().getParent();
                        if (root.identifierIndex.get("frameTimeCounter").isEmpty()) {
                            tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                                    "uniform float frameTimeCounter;");
                        }
                        if (mainDef instanceof FunctionDefinition) {
                            int mainIdx = tree.getChildren().indexOf(mainDef);

                            if ("_veil_alpha".equals(alphaSource)) {
                                tree.getChildren().add(mainIdx++,
                                        t.parseExternalDeclaration(root, "varying float _veil_alpha;"));
                            }
                            tree.getChildren().add(mainIdx,
                                    t.parseExternalDeclaration(root, IGN_FUNC));
                        }
                        tree.appendMainFunctionBody(t,
                                "if (" + alphaSource + " <= _veil_noise(gl_FragCoord.xy)) discard;");
                    });
                }
                return t.transform(fragmentSource, new ParseParams());
            } catch (Exception e) {
                IrisVeilCompat.LOGGER.warn("VeilDithering FragmentPatcher: AST injection failed for method={}", method, e);
                return fragmentSource;
            }
        }
    }
}
