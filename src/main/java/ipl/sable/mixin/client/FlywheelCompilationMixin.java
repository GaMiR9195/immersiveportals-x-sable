package ipl.sable.mixin.client;

import ipl.sable.render.IplClipUniformPatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds IP's portal clip (slot 0) and the sub-level clip slots ({@code 1..SLOTS}) to Flywheel
 * vertex shaders.
 *
 * <p>Flywheel compiles its programs itself, past Mojang's {@code Program}, Veil's
 * preprocessor and Iris's transformer, so none of the other GLSL injections reach instanced
 * visuals (Create cogs, Aeronautics handles, Simulated sub-models). Before
 * {@code compile(ShaderType, String)} this mixin rewrites {@code fullSource} of every vertex
 * shader that contains Flywheel's standard {@code gl_Position} line: it declares the clip
 * uniforms after {@code #version} and writes {@code gl_ClipDistance[0..SLOTS]} for the
 * camera-relative position {@code flw_vertexPos.xyz - flw_cameraPos} — the space of IP's
 * {@code activeClipPlaneEquationBeforeModelView}. The equations are uploaded on bind by
 * {@code IplProgramBindHook} ({@code FlywheelEmbeddedEnvironmentMixin} and the
 * {@code _glUseProgram} hook). Shaders without the anchor (fragment, compute, custom vertex
 * chains) stay unchanged.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation", remap = false)
public abstract class FlywheelCompilationMixin {

    /** Matches {@code #version 330 core}, {@code #version 460} etc. with the line break. */
    @Unique
    private static final Pattern IPL$VERSION = Pattern.compile("(#version\\s+\\d+(?:\\s+\\w+)?\\s*\\n)");

    /** Flywheel's standard vertex-shader output line. */
    @Unique
    private static final String IPL$ANCHOR = "gl_Position = flw_viewProjection * flw_vertexPos;";

    @Unique
    private static final String IPL$UNIFORM_DECLS =
        "\nuniform vec4 iportal_ClippingEquation;\n" +
            "uniform vec4 ipl_subLevelClipEquation[" + IplClipUniformPatcher.SLOTS + "];\n";

    @Unique
    private static final String IPL$CLIP_WRITES = ipl$clipWrites();

    /** Constant indices only: GLSL 1.50 sizes {@code gl_ClipDistance} by the largest one. */
    @Unique
    private static String ipl$clipWrites() {
        StringBuilder writes = new StringBuilder(IPL$ANCHOR).append("\n    {\n")
            .append("        vec3 _ipl_camRel = flw_vertexPos.xyz - flw_cameraPos;\n")
            .append("        gl_ClipDistance[0] = dot(_ipl_camRel, iportal_ClippingEquation.xyz)")
            .append(" + iportal_ClippingEquation.w;\n");
        for (int i = 0; i < IplClipUniformPatcher.SLOTS; i++) {
            writes.append("        gl_ClipDistance[").append(i + 1)
                .append("] = dot(_ipl_camRel, ipl_subLevelClipEquation[").append(i)
                .append("].xyz) + ipl_subLevelClipEquation[").append(i).append("].w;\n");
        }
        return writes.append("    }\n").toString();
    }

    @Shadow
    @Final
    private StringBuilder fullSource;

    @Inject(
        method = "compile",
        at = @At("HEAD")
    )
    private void ipl$injectFlywheelClipping(CallbackInfoReturnable<?> cir) {
        if (fullSource.indexOf(IPL$ANCHOR) < 0) return;

        String rewritten = fullSource.toString().replace(IPL$ANCHOR, IPL$CLIP_WRITES);
        // Declarations go right after #version, at top-level scope.
        Matcher m = IPL$VERSION.matcher(rewritten);
        String finalSrc = m.find()
            ? rewritten.substring(0, m.end()) + IPL$UNIFORM_DECLS + rewritten.substring(m.end())
            : IPL$UNIFORM_DECLS + rewritten;

        fullSource.setLength(0);
        fullSource.append(finalSrc);
    }
}
