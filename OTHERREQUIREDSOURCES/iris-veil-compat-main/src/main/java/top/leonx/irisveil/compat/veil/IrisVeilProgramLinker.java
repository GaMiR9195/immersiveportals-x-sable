package top.leonx.irisveil.compat.veil;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderSourceSet;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.api.client.render.shader.program.ShaderBlendMode;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTestFunction;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.accessors.ProgramDirectivesAccessor;
import top.leonx.irisveil.accessors.ProgramSourceAccessor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

/**
 * Creates Iris {@link ShaderInstance} objects for Veil shaders.
 *
 * <p>For each Veil shader path, this class:
 * <ol>
 *   <li>Gets the Veil {@link ShaderProgram} to extract the vertex format</li>
 *   <li>Attempts to read the Veil vertex shader source for injection</li>
 *   <li>Gets the Iris shaderpack's block program source</li>
 *   <li>Patches the block vertex shader to accept the Veil format
 *       (with AST-based attribute removal + Veil vertex injection if source available)</li>
 *   <li>Uses the block fragment shader as-is (it already outputs gbuffer)</li>
 *   <li>Creates an Iris {@link ShaderInstance} via the Iris rendering pipeline</li>
 * </ol>
 *
 * <p>If any step fails, returns {@code null} — the caller should fall back to
 * the original Veil shader.
 */
public class IrisVeilProgramLinker {

    public record Params(ProgramId programId, boolean useDithering) {}

    private static final Pattern INCLUDE_PATTERN =
        Pattern.compile("^\\s*#include\\s+([a-z0-9_.-]+:[a-z0-9_/.\\-]+)\\s*$", Pattern.MULTILINE);

    private final GlslTransformerVeilPatcher patcher = new GlslTransformerVeilPatcher();
    private final GlslTransformerVeilFragmentPatcher fragmentPatcher = new GlslTransformerVeilFragmentPatcher();
    private final Iterable<StringPair> environmentDefines = StandardMacros.createStandardEnvironmentDefines();

    /**
     * Creates an Iris {@link ShaderInstance} for the given Veil shader path.
     *
     * @param shaderPath   the Veil shader resource location (e.g. {@code aeronautics:levitite/levitite})
     * @param veilProgram  the compiled Veil shader program
     * @return a new Iris ShaderInstance, or {@code null} if creation failed
     */
    @Nullable
    public ShaderInstance create(ResourceLocation shaderPath, ShaderProgram veilProgram, ProgramId programId, boolean useDithering) {
        try {
            // 1. Get Iris rendering pipeline
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof IrisRenderingPipelineAccessor irisPipeline)) {
                IrisVeilCompat.LOGGER.warn("IrisVeilCompat: Iris pipeline not available for '{}'", shaderPath);
                return null;
            }

            // 2. Get shaderpack program set and resolver
            ProgramSet programSet = irisPipeline.getProgramSet();
            ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);

            // 3. Get vertex format from Veil program
            VertexFormat format = veilProgram.getFormat();
            if (format == null) {
                IrisVeilCompat.LOGGER.warn("IrisVeilCompat: Veil program '{}' has no vertex format", shaderPath);
                return null;
            }

            // 4. Read Veil vertex shader source for injection (best-effort, may be null)
            String veilVertSource = readVeilVertexSource(veilProgram);
            String veilFragSource = readVeilFragmentSource(veilProgram);

            if(!format.hasColor() && veilVertSource != null && veilVertSource.contains("Color"))
            {
                if(veilVertSource.contains("UV"))
                {
                    // in laser/laser. The vertex shader uses UV instead of UV0. ShaderProgramImpl.CompiledProgram.detectVertexFormat will fail to detect and fallback to DefaultVertexFormat.Position. We fix it here.
                    format = DefaultVertexFormat.POSITION_TEX_COLOR;
                    IrisVeilCompat.LOGGER.warn("IrisVeilCompat: Veil program '{}' has 'Color' but vertex format doesn't, IrisVeilCompat will modified the format to DefaultVertexFormat.POSITION_TEX_COLOR", shaderPath);
                }else{
                    format = DefaultVertexFormat.POSITION_COLOR;
                    IrisVeilCompat.LOGGER.warn("IrisVeilCompat: Veil program '{}' has 'Color' but vertex format doesn't, IrisVeilCompat will modified the format to DefaultVertexFormat.POSITION_COLOR", shaderPath);
                }
            }

            // 5. Dithered translucency already encodes alpha through screen-door
            // discard. A trailing Iris alpha test would incorrectly delete low
            // alpha laser pixels after the dither pass.
            // Shadow programs always use ALWAYS — alpha tests don't apply to
            // shadow map rendering.
            AlphaTest alphaTest;
            if (programId == ProgramId.Shadow) {
                alphaTest = AlphaTest.ALWAYS;
            } else if (useDithering || programId == ProgramId.EntitiesTrans) {
                alphaTest = AlphaTest.ALWAYS;
            } else {
                alphaTest = detectAlphaTest(veilProgram);
            }
            Optional<ProgramSource> sourceOpt = resolver.resolve(programId);
            if (programId == ProgramId.Shadow) {
                IrisVeilCompat.LOGGER.info("IrisVeilCompat: using Shadow program for '{}'", shaderPath);
            } else if (programId == ProgramId.EntitiesTrans) {
                IrisVeilCompat.LOGGER.info("IrisVeilCompat: using EntitiesTrans program for translucent '{}'", shaderPath);
            } else if (useDithering) {
                IrisVeilCompat.LOGGER.info("IrisVeilCompat: using Block program with dithering for translucent '{}'", shaderPath);
            } else {
                IrisVeilCompat.LOGGER.info("IrisVeilCompat: using Block program for opaque '{}'", shaderPath);
            }

            if (sourceOpt.isEmpty()) {
                IrisVeilCompat.LOGGER.warn("IrisVeilCompat: no {} program in shaderpack for '{}'", programId, shaderPath);
                return null;
            }
            ProgramSource source = sourceOpt.get();

            // 6. Get Iris shader sources
            String irisVertSource = source.getVertexSource().orElse(null);
            String irisFragSource = source.getFragmentSource().orElse(null);
            if (irisVertSource == null || irisFragSource == null) {
                IrisVeilCompat.LOGGER.warn("IrisVeilCompat: {} program has no vertex or fragment source for '{}'", programId, shaderPath);
                return null;
            }

            // 8. Build shader name (shadow programs use a distinct prefix so Iris
            // tracks them separately from gbuffer programs)
            boolean isShadow = programId == ProgramId.Shadow;
            String shaderName = (isShadow ? "shadow_veil_" : "gbuffers_veil_") +
                shaderPath.getNamespace() + "_" + shaderPath.getPath().replace('/', '_');

            // 9. Patch Iris vertex shader for Veil format. Shadow programs
            // still need the Veil vertex path; otherwise Rope-like shaders lose
            // their model-space deformation before Iris writes shadow depth.
            String patchedVertSource = patchVertexSource(
                irisVertSource,
                veilVertSource,
                format,
                shaderName,
                patcher);
            if (!isShadow) {
                irisFragSource = fragmentPatcher.patch(irisFragSource, veilFragSource, useDithering);
            }

            // 10. Apply dithering after Veil patching so the alpha varying is
            // not re-parsed and stripped by the main Veil transformer.
            if (useDithering) {
                VeilDitheringPatcher.DitherResult ditherResult = VeilDitheringPatcher.applyFragmentDithering(
                    patchedVertSource, irisFragSource, "IGN");
                patchedVertSource = ditherResult.patchedVertex();
                irisFragSource = ditherResult.patchedFragment();
            }

            patchedVertSource = JcppProcessor.glslPreprocessSource(patchedVertSource, environmentDefines);
            // Dump patched shader sources to files for debugging
            try {
                java.nio.file.Path debugDir = java.nio.file.Path.of("patched_shaders");
                java.nio.file.Files.createDirectories(debugDir);
                java.nio.file.Files.writeString(debugDir.resolve(shaderName + ".vsh"), patchedVertSource);
                if (irisFragSource != null) {
                    java.nio.file.Files.writeString(debugDir.resolve(shaderName + ".fsh"), irisFragSource);
                }
            } catch (Exception ex) {
                IrisVeilCompat.LOGGER.debug("Failed to dump shader for {}: {}", shaderName, ex.getMessage());
            }

            // 10. Create new ProgramSource with patched vertex + fragment
            var sourceAccessor = (ProgramSourceAccessor) source;
            ProgramSource veilProgramSource = new ProgramSource(
                shaderName,
                patchedVertSource,
                source.getGeometrySource().orElse(null),
                null, // no tessellation control
                null, // no tessellation eval
                irisFragSource,
                programSet,
                sourceAccessor.getShaderProperties(),
                sourceAccessor.getBlendModeOverride()
            ).withDirectiveOverride(source.getDirectives());

            // 11. Set alpha test override.
            // Skip for shadow — the shaderpack's own Shadow program directives
            // already carry the correct alpha test (e.g. alpha discard for
            // semi-transparent blocks). Overriding to ALWAYS would disable it.
            if (!isShadow) {
                ((ProgramDirectivesAccessor) veilProgramSource.getDirectives())
                    .irisveil$setAlphaTestOverride(alphaTest);
            }

            // 12. Create Iris ShaderInstance — use createShadowShader for shadow
            // passes so the program writes to the shadow map FBO rather than the gbuffer.
            ShaderInstance shader;
            if (isShadow) {
                shader = irisPipeline.invokeCreateShadowShader(
                    shaderName,
                    veilProgramSource,
                    programId,
                    alphaTest,
                    format,
                    false, false, false, false
                );
            } else {
                shader = irisPipeline.invokeCreateShader(
                    shaderName,
                    veilProgramSource,
                    programId,
                    alphaTest,
                    format,
                    FogMode.OFF,
                    false, false, false, false, false
                );
            }

            IrisVeilCompat.LOGGER.info("IrisVeilCompat: created Iris ShaderInstance for '{}'", shaderPath);
            return shader;

        } catch (Exception e) {
            IrisVeilCompat.LOGGER.error("IrisVeilCompat: failed to create Iris shader for '{}'", shaderPath, e);
            return null;
        }
    }

    /**
     * Determines the Iris program parameters for a Veil shader without creating the shader.
     *
     * @param veilProgram the compiled Veil shader program
     * @return the determined parameters, or {@code null} if the Iris pipeline is unavailable
     */
    @Nullable
    public Params determineParams(ShaderProgram veilProgram) {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipelineAccessor irisPipeline)) {
            return null;
        }
        ProgramSet programSet = irisPipeline.getProgramSet();
        ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);

        String veilVertSource = readVeilVertexSource(veilProgram);
        String veilFragSource = readVeilFragmentSource(veilProgram);
        boolean needsTranslucency = detectNeedsTranslucency(veilProgram, veilVertSource, veilFragSource);

        ProgramId programId;
        boolean useDithering;
        if (needsTranslucency) {
            if (resolver.has(ProgramId.EntitiesTrans)) {
                programId = ProgramId.EntitiesTrans;
                useDithering = false;
            } else {
                programId = ProgramId.Block;
                useDithering = true;
            }
        } else {
            programId = ProgramId.Block;
            useDithering = false;
        }
        return new Params(programId, useDithering);
    }

    static String patchVertexSource(
            String irisVertSource,
            String veilVertSource,
            VertexFormat format,
            String shaderName,
            GlslTransformerVeilPatcher patcher) {
        return patcher.patch(irisVertSource, veilVertSource, format, shaderName);
    }

    /**
     * Reads the Veil vertex shader source. First checks the cache populated by
     * {@link MixinDirectShaderCompiler} (which captures Veil's fully-processed
     * source during compilation). Falls back to reading from the Minecraft resource
     * system with manual {@code #include} resolution.
     *
     * @return the GLSL source string, or {@code null} if unavailable
     */
    @Nullable
    private String readVeilVertexSource(ShaderProgram veilProgram) {
        try {
            ProgramDefinition definition = veilProgram.getDefinition();
            if (definition == null || definition.vertex() == null) {
                return null;
            }

            ResourceLocation vertexId = definition.vertex();

            // 1. Check if processed source is cached (from MixinDirectShaderCompiler hook)
            String cached = IrisVeilShaderCache.getProcessedVertexSource(vertexId);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }

            // 2. Fallback: read raw source from resource system and resolve includes
            ShaderManager sm = VeilRenderSystem.renderer().getShaderManager();
            ShaderSourceSet sourceSet = sm.getSourceSet();
            ResourceLocation filePath = sourceSet.getTypeConverter(GL_VERTEX_SHADER).idToFile(vertexId);

            String source = readResource(filePath);
            if (source == null || source.isEmpty()) {
                return null;
            }

            // Resolve #include directives
            source = resolveIncludes(source, sourceSet, 0);

            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: read raw Veil vertex source for '{}' ({} chars)",
                veilProgram.getName(), source.length());
            return source;

        } catch (Exception e) {
            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: could not read Veil vertex source: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private String readVeilFragmentSource(ShaderProgram veilProgram) {
        try {
            ProgramDefinition definition = veilProgram.getDefinition();
            if (definition == null || definition.fragment() == null) {
                return null;
            }

            ResourceLocation fragmentId = definition.fragment();

            String cached = IrisVeilShaderCache.getProcessedFragmentSource(fragmentId);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }

            ShaderManager sm = VeilRenderSystem.renderer().getShaderManager();
            ShaderSourceSet sourceSet = sm.getSourceSet();
            ResourceLocation filePath = sourceSet.getTypeConverter(GL_FRAGMENT_SHADER).idToFile(fragmentId);

            String source = readResource(filePath);
            if (source == null || source.isEmpty()) {
                return null;
            }

            source = resolveIncludes(source, sourceSet, 0);

            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: read raw Veil fragment source for '{}' ({} chars)",
                veilProgram.getName(), source.length());
            return source;
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: could not read Veil fragment source: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Recursively resolves {@code #include <namespace:path>} directives.
     * Reads included files from the Minecraft resource system via Veil's include folder convention.
     */
    private String resolveIncludes(String source, ShaderSourceSet sourceSet, int depth) {
        if (depth > 5) {
            return source; // prevent infinite recursion
        }

        var matcher = INCLUDE_PATTERN.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String includeRef = matcher.group(1); // e.g. "veil:fog" or "aeronautics:levitite_utils"
            String resolved = resolveIncludeRef(includeRef, sourceSet, depth);
            matcher.appendReplacement(result, resolved != null ? resolved.replace("$", "\\$") : "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves a single include reference like {@code veil:fog} or {@code aeronautics:levitite_utils}
     * into the actual GLSL content. Reads from the Veil include directory convention:
     * {@code <namespace>:pinwheel/shaders/include/<name>.glsl}
     */
    @Nullable
    private String resolveIncludeRef(String includeRef, ShaderSourceSet sourceSet, int depth) {
        try {
            String[] parts = includeRef.split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            String namespace = parts[0];
            String name = parts[1];

            // Convert to Veil include path: <namespace>:pinwheel/shaders/include/<name>.glsl
            ResourceLocation includePath = ResourceLocation.fromNamespaceAndPath(namespace,
                "pinwheel/shaders/include/" + name + ".glsl");

            String included = readResource(includePath);
            if (included != null) {
                // Recursively resolve includes within the included file
                return resolveIncludes(included, sourceSet, depth + 1);
            }
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: failed to resolve include '{}': {}", includeRef, e.getMessage());
        }

        return null;
    }

    /**
     * Reads a resource file from the Minecraft resource manager.
     */
    @Nullable
    private String readResource(ResourceLocation location) {
        try {
            var resourceManager = Minecraft.getInstance().getResourceManager();
            Optional<Resource> optResource = resourceManager.getResource(location);
            if (optResource.isEmpty()) {
                return null;
            }
            try (var reader = new BufferedReader(
                    new InputStreamReader(optResource.get().open(), StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
                return content.toString();
            }
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.debug("IrisVeilCompat: could not read resource '{}': {}", location, e.getMessage());
            return null;
        }
    }

    /**
     * Determines whether a Veil shader requires translucent rendering.
     *
     * <p>Checks in order:
     * <ol>
     *   <li>Veil blend mode for alpha-related blend factors</li>
     *   <li>Vertex source for alpha manipulation patterns</li>
     *   <li>Shader path keywords suggesting translucency</li>
     * </ol>
     */
    private static boolean detectNeedsTranslucency(ShaderProgram veilProgram, String veilVertSource, String veilFragSource) {
        // 1. Primary: check blend mode for alpha-related factors
        ProgramDefinition definition = veilProgram.getDefinition();
        if (definition != null) {
            ShaderBlendMode blendMode = definition.blendMode();
            if (blendMode != null) {
                boolean hasAlphaFactor =
                    blendMode.srcColorFactor() == com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA ||
                    blendMode.srcColorFactor() == com.mojang.blaze3d.platform.GlStateManager.SourceFactor.CONSTANT_ALPHA ||
                    blendMode.dstColorFactor() == com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA ||
                    blendMode.dstColorFactor() == com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA ||
                    blendMode.srcAlphaFactor() == com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA ||
                    blendMode.srcAlphaFactor() == com.mojang.blaze3d.platform.GlStateManager.SourceFactor.CONSTANT_ALPHA ||
                    blendMode.dstAlphaFactor() == com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA ||
                    blendMode.dstAlphaFactor() == com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA;
                if (hasAlphaFactor) {
                    return true;
                }
            }
        }

        // 2. Secondary: parse vertex source for alpha patterns
        if (veilVertSource != null && !veilVertSource.isEmpty()) {
            if (veilVertSource.contains("vertexColor.a") || veilVertSource.contains("gl_Color.a")) {
                return true;
            }

            // Assignments of alpha < 1.0
            int idx = 0;
            while (true) {
                int dotA = veilVertSource.indexOf(".a", idx);
                if (dotA == -1) dotA = veilVertSource.indexOf(".w", idx);
                if (dotA == -1) break;

                int eq = veilVertSource.indexOf('=', dotA);
                if (eq == -1 || eq > dotA + 4) {
                    idx = dotA + 1;
                    continue;
                }

                int semi = veilVertSource.indexOf(';', eq);
                if (semi == -1) semi = veilVertSource.length();

                String expr = veilVertSource.substring(eq + 1, semi).trim();
                if (expr.contains("0.") && !expr.contains("1.0")) {
                    return true;
                }

                idx = dotA + 1;
            }

            // mix() / smoothstep() affecting alpha
            boolean hasMixOrSmoothstep = veilVertSource.contains("mix(") || veilVertSource.contains("smoothstep(");
            if (hasMixOrSmoothstep) {
                int mixIdx = veilVertSource.indexOf("mix(");
                if (mixIdx == -1) mixIdx = veilVertSource.indexOf("smoothstep(");
                while (mixIdx != -1) {
                    int semi = veilVertSource.indexOf(';', mixIdx);
                    if (semi == -1) semi = veilVertSource.length();
                    String stmt = veilVertSource.substring(mixIdx, semi);
                    if (stmt.contains(".a") || stmt.contains(".w")) {
                        return true;
                    }
                    mixIdx = veilVertSource.indexOf("mix(", semi);
                    if (mixIdx == -1) mixIdx = veilVertSource.indexOf("smoothstep(", semi);
                }
            }
        }

        // 3. Fragment alpha is the important case for laser-like Veil shaders:
        // their vertex color may be opaque, while the fragment shader tapers or
        // fades alpha based on varyings such as lengthData.
        if (veilFragSource != null && !veilFragSource.isEmpty()) {
            String compact = veilFragSource.replaceAll("\\s+", "");
            if (compact.contains(".a*=") ||
                compact.contains(".a=") ||
                compact.contains("fragColor=color*") ||
                compact.contains("fragColor=linear_fog") ||
                compact.contains("fragColor=vec4(")) {
                return true;
            }
        }

        // 4. Tertiary: check shader path keywords
        ResourceLocation name = veilProgram.getName();
        if (name != null) {
            String lowerPath = name.getPath().toLowerCase();
            if (lowerPath.contains("translucent") || lowerPath.contains("glass") ||
                lowerPath.contains("alpha") || lowerPath.contains("trans") ||
                lowerPath.contains("laser")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines the appropriate alpha test.
     * Uses ONE_TENTH as a safe default for textured geometry with potential alpha cutout.
     */
    private AlphaTest detectAlphaTest(ShaderProgram veilProgram) {
        return new AlphaTest(AlphaTestFunction.GREATER, 0.1f);
    }
}
