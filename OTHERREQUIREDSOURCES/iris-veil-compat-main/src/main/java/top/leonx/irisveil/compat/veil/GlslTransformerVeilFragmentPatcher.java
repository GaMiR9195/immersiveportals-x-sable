package top.leonx.irisveil.compat.veil;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import top.leonx.irisveil.IrisVeilCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges Veil fragment color into a shaderpack fragment program.
 *
 * <p>The shaderpack fragment still owns the final gbuffer writes. Veil fragment
 * code is renamed into a helper function that computes {@code _veil_fragColor};
 * common shaderpack albedo expressions are then redirected to that color.</p>
 */
public class GlslTransformerVeilFragmentPatcher {
    private static final Pattern VERSION_LINE =
        Pattern.compile("(?m)^\\s*#version\\s+\\d+(?:\\s+\\w+)?\\s*$");
    private static final Pattern VERSION_NUMBER =
        Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+\\w+)?\\s*$");
    private static final Pattern MAIN_DECL =
        Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern OUT_FRAG_COLOR_DECL =
        Pattern.compile("\\bout\\s+vec4\\s+_veil_fragColor\\s*;");
    private static final Pattern VEIL_TIME_UNIFORM =
        Pattern.compile("\\buniform\\s+float\\s+time\\s*;");
    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^.*#version\\s+(\\d+)(\\s+\\w+)?", Pattern.DOTALL);

    private static final Set<String> BASE_TEXTURE_SAMPLERS = Set.of(
        "gtexture", "texture", "tex", "iris_Texture"
    );

    private static final Set<String> TEXTURE_FUNCTIONS = Set.of(
        "texture", "texture2D", "textureLod", "textureGrad",
        "textureProj", "textureProjLod", "textureProjGrad"
    );

    private final SingleASTTransformer<FragmentPatchParams> transformer;
    @SuppressWarnings("rawtypes")
    private final SingleASTTransformer veilTransformer;
    @SuppressWarnings("rawtypes")
    private final SingleASTTransformer irisDeclarationTransformer;

    public GlslTransformerVeilFragmentPatcher() {
        transformer = new SingleASTTransformer<>() {
            {
                setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
            }

            @Override
            public TranslationUnit parseTranslationUnit(Root rootInstance, String input) {
                Matcher matcher = VERSION_PATTERN.matcher(input);
                if (!matcher.find()) {
                    input = "#version 400 compatibility\n" + input;
                    transformer.getLexer().version = Version.GLSL40;
                    return super.parseTranslationUnit(rootInstance, input);
                }

                int originalVersion = Integer.parseInt(matcher.group(1));
                int finalVersion = Math.max(originalVersion, 400);
                input = matcher.replaceAll("#version " + finalVersion + " compatibility\n");
                transformer.getLexer().version = Version.fromNumber(finalVersion);
                return super.parseTranslationUnit(rootInstance, input);
            }
        };
        transformer.setTransformation(this::transformShaderpackFragment);

        veilTransformer = new SingleASTTransformer();
        veilTransformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        veilTransformer.getLexer().version = Version.GLSL40;

        irisDeclarationTransformer = new SingleASTTransformer();
        irisDeclarationTransformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        irisDeclarationTransformer.getLexer().version = Version.GLSL40;
    }

    public String patch(String irisFragmentSource, String veilFragmentSource, boolean useDithering) {
        if (irisFragmentSource == null || veilFragmentSource == null || veilFragmentSource.isEmpty()) {
            return irisFragmentSource;
        }

        try {
            String veilSource = JcppProcessor.glslPreprocessSource(veilFragmentSource,
                List.of(new StringPair("VEIL_FRAGMENT", "1")));
            String baseSampler = detectBaseSampler(irisFragmentSource);
            String patchedIris = transformer.transform(irisFragmentSource, new FragmentPatchParams(useDithering));
            patchedIris = forceCompatibilityVersion(patchedIris);
            Set<String> irisGlobalNames = extractGlobalDeclarationNames(irisDeclarationTransformer, patchedIris);
            if (VEIL_TIME_UNIFORM.matcher(veilSource).find() && !irisGlobalNames.contains("frameTimeCounter")) {
                patchedIris = injectAfterPreamble(patchedIris, "\nuniform float frameTimeCounter;\n");
                irisGlobalNames.add("frameTimeCounter");
            }
            String veilBridge = buildVeilBridge(veilSource, baseSampler, veilTransformer,
                irisGlobalNames);
            patchedIris = injectAfterPreamble(patchedIris, "\nvec4 _veil_fragColor;\n");
            patchedIris = injectBeforeMain(patchedIris, veilBridge);
            patchedIris = injectVeilMainCall(patchedIris);
            return patchedIris;
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.warn("IrisVeilFragmentPatcher: failed, using shaderpack fragment as-is", e);
            return irisFragmentSource;
        }
    }

    private record FragmentPatchParams(boolean useDithering) implements JobParameters {}

    private void transformShaderpackFragment(TranslationUnit tree, Root root, FragmentPatchParams params) {
        String replacement = params.useDithering
            ? "vec4(_veil_fragColor.rgb, 1.0)"
            : "_veil_fragColor";

        var textureCalls = new ArrayList<Expression>();
        root.nodeIndex.getStream(FunctionCallExpression.class)
            .filter(this::isTextureFunctionCall)
            .filter(this::usesBaseTextureSampler)
            .forEach(textureCalls::add);

        if (!textureCalls.isEmpty()) {
            root.replaceExpressions(transformer, textureCalls.stream(), replacement);
        }

        IrisVeilCompat.LOGGER.debug(
            "IrisVeilFragmentPatcher: redirected {} base texture sample(s) to {}",
            textureCalls.size(), replacement);
    }

    private boolean isTextureFunctionCall(FunctionCallExpression call) {
        var functionName = call.getFunctionName();
        return functionName != null && TEXTURE_FUNCTIONS.contains(functionName.getName());
    }

    private boolean usesBaseTextureSampler(FunctionCallExpression call) {
        if (call.getParameters().isEmpty()) {
            return false;
        }

        String sampler = ASTPrinter.printSimple(call.getParameters().getFirst()).trim();
        return BASE_TEXTURE_SAMPLERS.contains(sampler);
    }

    @SuppressWarnings("unchecked")
    private static String buildVeilBridge(String source, String baseSampler,
                                          SingleASTTransformer veilTransformer,
                                          Set<String> irisUniformNames) {
        boolean hasTimeUniform = VEIL_TIME_UNIFORM.matcher(source).find();
        TranslationUnit veilTree = veilTransformer.parseSeparateTranslationUnit(source);
        Root veilRoot = veilTree.getRoot();

        for (String name : extractVeilGlobalNames(veilTree)) {
            veilRoot.rename(name, "_veil_" + name);
        }
        if (hasTimeUniform) {
            veilRoot.rename("time", "_veil_time");
        }

        String result = VERSION_LINE.matcher(ASTPrinter.printSimple(veilTree)).replaceAll("");
        result = removeMappedUniformDeclarations(result);
        result = removeDuplicateUniformDeclarations(result, irisUniformNames);

        for (var entry : VEIL_TO_IRIS.entrySet()) {
            result = result.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
                Matcher.quoteReplacement(entry.getValue()));
        }
        result = result.replaceAll("\\bSampler0\\b", Matcher.quoteReplacement(baseSampler));
        result = result.replaceAll("\\bTextureSheet\\b", Matcher.quoteReplacement(baseSampler));

        result = OUT_FRAG_COLOR_DECL.matcher(result).replaceAll("");
        result = MAIN_DECL.matcher(result).replaceFirst("void _veil_fragment_main(");
        result = result.replace("uniform float _veil_time;", "float _veil_time;");
        if (hasTimeUniform) {
            result = result.replaceFirst("\\bvoid\\s+_veil_fragment_main\\s*\\(\\s*\\)\\s*\\{",
                "void _veil_fragment_main() { _veil_time = frameTimeCounter;");
        }
        return "\n// IrisVeilCompat: Veil fragment bridge\n" + result + "\n";
    }

    private static Set<String> extractVeilGlobalNames(TranslationUnit veilTree) {
        Set<String> names = new LinkedHashSet<>();
        for (var child : veilTree.getChildren()) {
            if (child instanceof FunctionDefinition functionDefinition) {
                String name = functionDefinition.getFunctionPrototype().getName().getName();
                if (!"main".equals(name) && shouldRenameVeilGlobal(name)) {
                    names.add(name);
                }
                continue;
            }
            if (child instanceof DeclarationExternalDeclaration declarationExternalDeclaration
                && declarationExternalDeclaration.getDeclaration() instanceof TypeAndInitDeclaration declaration) {
                if (hasStorageQualifier(declaration, StorageType.UNIFORM)) {
                    continue;
                }
                declaration.getMembers().stream()
                    .map(member -> member.getName().getName())
                    .filter(GlslTransformerVeilFragmentPatcher::shouldRenameVeilGlobal)
                    .forEach(names::add);
            }
        }
        return names;
    }

    private static boolean shouldRenameVeilGlobal(String name) {
        return !name.startsWith("gl_")
            && !name.startsWith("iris_")
            && !name.startsWith("_veil_");
    }

    private static boolean hasStorageQualifier(TypeAndInitDeclaration declaration, StorageType... storageTypes) {
        var typeQualifier = declaration.getType().getTypeQualifier();
        if (typeQualifier == null) {
            return false;
        }

        return typeQualifier.getParts().stream()
            .filter(StorageQualifier.class::isInstance)
            .map(StorageQualifier.class::cast)
            .anyMatch(qualifier -> Arrays.asList(storageTypes).contains(qualifier.storageType));
    }

    private static final Map<String, String> VEIL_TO_IRIS = Map.of(
        "ColorModulator", "vec4(1.0)",
        "FogStart", "1.0e20",
        "FogEnd", "1.0e20",
        "FogColor", "vec4(0.0)"
    );

    private static String detectBaseSampler(String source) {
        for (String sampler : List.of("gtexture", "tex", "texture", "iris_Texture")) {
            if (Pattern.compile("\\buniform\\s+sampler\\w*\\s+" + Pattern.quote(sampler) + "\\s*;").matcher(source).find()) {
                return sampler;
            }
        }
        return "gtexture";
    }

    private static String removeMappedUniformDeclarations(String source) {
        String result = source;
        for (String name : VEIL_TO_IRIS.keySet()) {
            result = result.replaceAll("(?m)^\\s*uniform\\s+\\w+\\s+" + Pattern.quote(name) + "\\s*;\\s*\\R?", "");
        }
        result = result.replaceAll("(?m)^\\s*uniform\\s+\\w+\\s+Sampler0\\s*;\\s*\\R?", "");
        result = result.replaceAll("(?m)^\\s*uniform\\s+\\w+\\s+TextureSheet\\s*;\\s*\\R?", "");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> extractGlobalDeclarationNames(SingleASTTransformer transformer, String source) {
        Set<String> names = new LinkedHashSet<>();
        TranslationUnit tree = transformer.parseSeparateTranslationUnit(source);
        for (var child : tree.getChildren()) {
            if (child instanceof DeclarationExternalDeclaration declarationExternalDeclaration
                && declarationExternalDeclaration.getDeclaration() instanceof TypeAndInitDeclaration declaration) {
                declaration.getMembers().stream()
                    .map(member -> member.getName().getName())
                    .forEach(names::add);
            }
        }
        return names;
    }

    private static String removeDuplicateUniformDeclarations(String veilCode, Set<String> irisGlobalNames) {
        String result = veilCode;
        for (String name : irisGlobalNames) {
            result = result.replaceAll(
                "(?m)^\\s*uniform\\s+\\w+\\s+" + Pattern.quote(name)
                    + "\\s*(?:\\[[^]]*])?\\s*;\\s*\\R?", "");
        }
        return result;
    }

    private static String forceCompatibilityVersion(String source) {
        Matcher matcher = VERSION_NUMBER.matcher(source);
        if (!matcher.find()) {
            return "#version 400 compatibility\n" + source;
        }

        int version = Integer.parseInt(matcher.group(1));
        if (version >= 400) {
            return source;
        }

        return matcher.replaceFirst("#version 400 compatibility");
    }

    private static String injectBeforeMain(String irisSource, String bridge) {
        Matcher matcher = MAIN_DECL.matcher(irisSource);
        if (!matcher.find()) {
            return irisSource + bridge;
        }

        int insertAt = matcher.start();
        return irisSource.substring(0, insertAt) + "\n" + bridge + irisSource.substring(insertAt);
    }

    private static String injectAfterPreamble(String source, String injection) {
        int offset = 0;
        int insertAt = 0;
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#version") || trimmed.startsWith("#extension") || trimmed.isEmpty()) {
                offset += line.length() + 1;
                insertAt = offset;
                continue;
            }
            break;
        }
        return source.substring(0, insertAt) + injection + source.substring(insertAt);
    }

    private static String injectVeilMainCall(String source) {
        Matcher matcher = MAIN_DECL.matcher(source);
        if (!matcher.find()) {
            return source;
        }

        int brace = source.indexOf('{', matcher.end());
        if (brace < 0) {
            return source;
        }

        String call = "\n_veil_fragColor = vec4(1.0);\n_veil_fragment_main();\n";
        return source.substring(0, brace + 1) + call + source.substring(brace + 1);
    }
}
