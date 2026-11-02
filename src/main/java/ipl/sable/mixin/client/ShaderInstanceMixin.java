package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import ipl.sable.render.IplProgramRegistry;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

/**
 * Шейдеры ванили: свои uniform-ы плоскостей отсечения саб-уровня, регистрация слинкованной
 * программы для хука привязки и (по флагу) проверка, что линкер их не выкинул.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin implements IplSubLevelClipShader {

    // ---- Uniform-ы плоскостей отсечения ----
    // Registers our own per-sub-level clip uniform on each {@link ShaderInstance}.
    //
    // <p>Mirrors IP's approach in {@code qouteall.imm_ptl.core.mixin.client.render.shader.MixinShaderInstance}
    // which adds the {@code iportal_ClippingEquation} uniform. We add a parallel
    // uniform {@code ipl_subLevelClipEquation} on the same shaders so the GLSL the
    // shader-source transformation in {@code shader_transformation.yaml} injects
    // has a backing {@link Uniform} object for our render code to {@code set()} /
    // {@code upload()}.
    //
    // <p><b>Scope:</b> we only add the uniform when
    // {@link ShaderCodeTransformation#shouldAddUniform} returns true, so we don't
    // pay the cost on shaders that never see our discard injection. (And the shader
    // compile would fail if the uniform Java object referenced a name not in the
    // GLSL source.)
    //
    // <p>Note this mixin doesn't conflict with IP's mixin on the same method --
    // mixin multi-injects are fine as long as the injected method bodies don't
    // collide on shared state, and they don't here (each adds a distinct uniform
    // to {@code uniforms}).

    @Shadow
    @Final
    private List<Uniform> uniforms;

    @Shadow
    @Final
    private String name;

    @Unique
    @Nullable
    private Uniform ipl$subLevelClipEquation;

    @Unique
    @Nullable
    private Uniform ipl$subLevelClipEquation2;

    /**
     * Set of shader names whose GLSL source actually gets {@code ipl_subLevelClipEquation}
     * declared by our transformation entry. Must stay in sync with the affectedShaders
     * list in our {@code shader_transformation.yaml} entry that injects our slot-1
     * uniform + write.
     *
     * <p>We can't reuse IP's {@code ShaderCodeTransformation.shouldAddUniform(name)} as
     * a filter here -- that returns true for any shader in any of IP's entries, including
     * IP-only entries whose GLSL doesn't declare our uniform. Registering a Java-side
     * {@code Uniform} on those shaders would log "Shader X could not find uniform
     * named ipl_subLevelClipEquation" warnings on every compile.
     */
    @Unique
    private static final Set<String> IPL$AFFECTED_SHADERS = Set.of(
        // Vanilla rendertypes (Sable-overridden in this fork). Sub-level
        // block entities -- Create cogs via KineticBlockEntityRenderer chief
        // among them -- bind these inside RenderDispatcherBlockEntityClipMixin's
        // bracket. The terrain YAML entry injects gl_ClipDistance[1]; register
        // the Java Uniform here so IplClipUniformPatcher.patchForSubLevel
        // resolves and uploads to it via the setShader path.
        "rendertype_entity_solid",
        "rendertype_entity_cutout",
        "rendertype_entity_cutout_no_cull",
        "rendertype_entity_cutout_no_cull_z_offset",
        "rendertype_item_entity_translucent_cull",
        "rendertype_entity_translucent_cull",
        "rendertype_entity_translucent",
        "rendertype_entity_smooth_cutout",
        "rendertype_beacon_beam",
        "rendertype_entity_translucent_emissive",
        "particle",
        "rendertype_text",
        "rendertype_text_intensity",
        "rendertype_text_see_through",
        "rendertype_text_intensity_see_through",
        "rendertype_text_background",
        "rendertype_text_background_see_through",
        "rendertype_leash",
        "rendertype_lightning",
        "rendertype_solid",
        "rendertype_cutout",
        "rendertype_cutout_mipped",
        "rendertype_translucent",
        "terrain_solid",
        "terrain_cutout",
        "terrain_translucent",
        "entities_solid",
        "entities_cutout",
        "entities_translucent",
         // Iris-rewritten vanilla entity shader (no shaderpack). The YAML
        // injection for this name declares `uniform vec4 ipl_subLevelClipEquation;`
        // and writes gl_ClipDistance[1] -- so the Java-side Uniform needs
        // to be registered too, otherwise Mojang's updateLocations won't
        // find a backing Uniform object for it.
        "block_entity_diffuse",
        // Iris-rewritten moving-block / particle (Create cogs and animated
        // mechanical visuals; particle effects under shader pack). YAML
        // injects the slot-1 uniform decl for these; register the Java
        // Uniform here so updateLocations resolves it.
        "moving_block",
        "particles",
        // Veil-managed Simulated / Aeronautics block-entity sub-shaders.
        // YAML injects the slot-1 uniform for these; register the Java
        // Uniform here so updateLocations resolves it (avoids
        // 'could not find uniform' warnings and gives our _glUseProgram
        // write path a non-null location).
        "simulated:end_sea",
        "simulated:spread_end_sea",
        "simulated:rope/rope",
        "simulated:redstone_accumulator/diode",
        "simulated:spring/spring",
        "simulated:laser_pointer/lens",
        "simulated:contraption_diagram/outline_diagram",
        "simulated:laser/laser",
        "aeronautics:levitite/levitite",
        "aeronautics:burner_flame",
        "aeronautics:hot_air_overlay",
        "aeronautics:soft_light",
        // Create / NeoForge / Veil entity-class shaders that also use the
        // vanilla Position/ModelViewMat layout. Slot-1 Uniform registered
        // here so updateLocations resolves it.
        "glowing_shader",
        "rendertype_entity_unlit_translucent",
        "veil:necromancer/skinned_mesh"
    );

    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("HEAD")
    )
    private void ipl$onLoadReferences(CallbackInfo ci) {
        Shader self = (Shader) (Object) this;

        if (IPL$AFFECTED_SHADERS.contains(name)) {
            // type=7 (UT_FLOAT4), count=4 -- matches IP's Uniform configuration for
            // its own clipping equation uniform, which has the same vec4 layout.
            ipl$subLevelClipEquation = new Uniform(
                "ipl_subLevelClipEquation",
                7, 4, self
            );
            // Initialize to the no-clip identity (0,0,0,1) so the first frame --
            // before any sub-level bracket has run -- evaluates
            // dot(pos, (0,0,0)) + 1 = 1 > 0 = kept. Without this, the uniform
            // starts at zero, the shader writes gl_ClipDistance[1] = 0, which
            // is on the boundary and driver-dependent. Matches what
            // IplClipUniformPatcher.clearAndUpload restores on bracket exit.
            ipl$subLevelClipEquation.set(0f, 0f, 0f, 1f);
            uniforms.add(ipl$subLevelClipEquation);

            // Element [1] of the array uniform: querying "name[1]" resolves the second
            // element's own location (the base name resolves element [0] per GL spec).
            // Neutral (0,0,0,1) start: min(d0, 1) = d0 — single-cut behavior until a
            // multi-straddle bracket writes a real second plane.
            ipl$subLevelClipEquation2 = new Uniform(
                "ipl_subLevelClipEquation[1]",
                7, 4, self
            );
            ipl$subLevelClipEquation2.set(0f, 0f, 0f, 1f);
            uniforms.add(ipl$subLevelClipEquation2);
        }
    }

    @Inject(method = "apply", at = @At("HEAD"))
    private void ipl$prepareCurrentClipUniforms(CallbackInfo ci) {
        ipl.sable.render.IplProgramBindHook.prepareShader((ShaderInstance) (Object) this);
    }

    @Nullable
    @Override
    public Uniform ipl$getSubLevelClipUniform() {
        return ipl$subLevelClipEquation;
    }

    @Nullable
    @Override
    public Uniform ipl$getSubLevelClipUniform2() {
        return ipl$subLevelClipEquation2;
    }

    // ---- Регистрация программы и диагностика (-Dipl.portalDiagnostics) ----
    // Probe C: at RETURN of {@link ShaderInstance#updateLocations()}, enumerate every
    // active uniform the linker kept in the program via {@code glGetActiveUniform},
    // and report whether {@code iportal_ClippingEquation} (and our slot-1
    // {@code ipl_subLevelClipEquation}) survived.
    //
    // <p>This is the definitive answer for the open question "did the GLSL linker
    // strip our uniform?" The {@code "Shader X could not find uniform named Y"}
    // warnings tell us glGetUniformLocation returned -1, but that's a side-effect
    // symptom. Listing the actual active-uniforms set distinguishes:
    //
    // <ul>
    // <li><b>Uniform missing from list</b> — linker stripped it. Our injection
    // made it into source but the optimizer decided the gl_ClipDistance write
    // was dead. Hypothesis space: gl_PerVertex redeclaration, glEnable
    // state visibility, driver bug.</li>
    // <li><b>Uniform present in list but glGetUniformLocation returns -1</b> —
    // Mojang-side bug: we're querying location on the wrong program, or the
    // resolution call is happening before link. Different fix entirely.</li>
    // </ul>
    //
    // <p>Only logs once per (shader name, uniform set) pair to avoid spam, and only
    // for shaders that IP's {@code ShaderCodeTransformation.shouldAddUniform}
    // reports as in the affectedShaders list (those are the ones we care about).

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-shader-uniform-probe");

    @Unique
    private static final ConcurrentMap<String, Boolean> IPL$PROBED = new ConcurrentHashMap<>();
    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("RETURN")
    )
    private void ipl$probeActiveUniforms(CallbackInfo ci) {
        int linkedProgram = ((Shader) (Object) this).getId();
        ipl.sable.render.IplProgramBindHook.forgetProgram(linkedProgram);
        IplProgramRegistry.register(linkedProgram, name);
        // Only probe shaders we expect to carry iportal_ClippingEquation.
        if (!ShaderCodeTransformation.shouldAddUniform(name)) {
            return;
        }

        Shader self = (Shader) (Object) this;
        int programId = self.getId();

        if (!Boolean.getBoolean("ipl.portalDiagnostics") || IPL$PROBED.putIfAbsent(name, Boolean.TRUE) != null) {
            return;
        }

        if (programId == 0) {
            IPL$LOG.warn("[IPL-PROBE-C] name='{}' programId=0 -- can't enumerate, link likely failed", name);
            return;
        }

        // Link-status check first. If link failed, the program info log explains why.
        IntBuffer statusBuf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(programId, GL20.GL_LINK_STATUS, statusBuf);
        int linkStatus = statusBuf.get(0);

        IntBuffer countBuf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(programId, GL20.GL_ACTIVE_UNIFORMS, countBuf);
        int activeUniformCount = countBuf.get(0);

        String programLog = GL20.glGetProgramInfoLog(programId, 4096);
        boolean haveLog = programLog != null && !programLog.isEmpty();

        List<String> uniformNames = new ArrayList<>();
        boolean clipPresent = false;
        boolean subLevelClipPresent = false;
        for (int i = 0; i < activeUniformCount; i++) {
            IntBuffer sizeBuf = BufferUtils.createIntBuffer(1);
            IntBuffer typeBuf = BufferUtils.createIntBuffer(1);
            String uName = GL20.glGetActiveUniform(programId, i, sizeBuf, typeBuf);
            int loc = GL20.glGetUniformLocation(programId, uName);
            uniformNames.add(uName + "@" + loc + ":t=" + typeBuf.get(0) + ":s=" + sizeBuf.get(0));
            if ("iportal_ClippingEquation".equals(uName)) clipPresent = true;
            if ("ipl_subLevelClipEquation".equals(uName)) subLevelClipPresent = true;
        }

        IPL$LOG.info(
            "[IPL-PROBE-C] name='{}' linkStatus={} activeUniforms={} iportalClip={} subLevelClip={}{}",
            name,
            linkStatus == GL20.GL_TRUE ? "OK" : ("FAIL(" + linkStatus + ")"),
            activeUniformCount,
            clipPresent,
            subLevelClipPresent,
            haveLog ? "\n----- PROGRAM INFO LOG -----\n" + programLog.trim() + "\n----- END LOG -----" : ""
        );
        IPL$LOG.info("[IPL-PROBE-C] name='{}' uniforms={}", name, uniformNames);
    }
}
