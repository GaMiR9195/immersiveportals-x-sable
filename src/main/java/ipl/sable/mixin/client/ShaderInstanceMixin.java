package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import ipl.sable.render.IplClipUniformPatcher;
import ipl.sable.render.IplProgramBindHook;
import ipl.sable.render.IplProgramRegistry;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
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

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Шейдеры ванили: свои uniform-ы плоскостей отсечения саб-уровня, регистрация слинкованной
 * программы для хука привязки и (по флагу) проверка, что линкер их не выкинул.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin implements IplSubLevelClipShader {

    // ---- Uniform-ы плоскостей отсечения ----
    // Like IP's MixinShaderInstance adds iportal_ClippingEquation, this adds the backing
    // Uniform objects for the GLSL that shader_transformation.yaml injects
    // (ipl_subLevelClipEquation[0..SLOTS-1]). Each mixin adds distinct uniforms, so both
    // injections into updateLocations coexist.

    @Shadow
    @Final
    private List<Uniform> uniforms;

    @Shadow
    @Final
    private String name;

    /** One Uniform per array element; null while the shader does not declare the array. */
    @Unique
    @Nullable
    private Uniform[] ipl$subLevelClipEquation;

    /**
     * Shaders whose GLSL actually declares {@code ipl_subLevelClipEquation}; must match the
     * affectedShaders of our entries in {@code shader_transformation.yaml}. IP's
     * {@code ShaderCodeTransformation.shouldAddUniform} is broader (it covers IP-only entries
     * too), and a Java-side Uniform without a GLSL declaration logs "could not find uniform"
     * on every compile.
     */
    @Unique
    private static final Set<String> IPL$AFFECTED_SHADERS = Set.of(
        // Vanilla rendertypes: sub-level terrain and block entities (Create cogs via
        // KineticBlockEntityRenderer among them).
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
        // Iris-rewritten vanilla entity shader (no shader pack).
        "block_entity_diffuse",
        // Iris-rewritten moving-block / particle (Create cogs and animated
        // mechanical visuals; particle effects under a shader pack).
        "moving_block",
        "particles",
        // Veil-managed Simulated / Aeronautics block-entity shaders.
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
        // Create / NeoForge / Veil entity-class shaders with the vanilla
        // Position/ModelViewMat layout.
        "glowing_shader",
        "rendertype_entity_unlit_translucent",
        "veil:necromancer/skinned_mesh"
    );

    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("HEAD")
    )
    private void ipl$addSubLevelClipUniforms(CallbackInfo ci) {
        if (!IPL$AFFECTED_SHADERS.contains(name)) return;
        Shader self = (Shader) (Object) this;
        // type=7 (UT_FLOAT4), count=4: the same vec4 layout as IP's iportal_ClippingEquation.
        // "name[i]" resolves each array element's own location (the base name is [0]).
        Uniform[] slots = new Uniform[IplClipUniformPatcher.SLOTS];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = ipl$addNeutralUniform(IplProgramBindHook.subLevelUniformName(i), self);
        }
        ipl$subLevelClipEquation = slots;
    }

    /**
     * Starts at the no-clip {@code (0,0,0,1)} that {@code IplClipUniformPatcher.clearAndUpload}
     * also restores: distance 1, vertex kept. A zero uniform would put every vertex exactly on
     * the clip boundary, which is driver-dependent.
     */
    @Unique
    private Uniform ipl$addNeutralUniform(String uniformName, Shader self) {
        Uniform uniform = new Uniform(uniformName, 7, 4, self);
        uniform.set(0f, 0f, 0f, 1f);
        uniforms.add(uniform);
        return uniform;
    }

    @Inject(method = "apply", at = @At("HEAD"))
    private void ipl$prepareCurrentClipUniforms(CallbackInfo ci) {
        IplProgramBindHook.prepareShader((ShaderInstance) (Object) this);
    }

    @Nullable
    @Override
    public Uniform ipl$getSubLevelClipUniform(int slot) {
        Uniform[] slots = ipl$subLevelClipEquation;
        return slots == null ? null : slots[slot];
    }

    // ---- Регистрация программы и диагностика ----

    @Unique
    private static final boolean IPL$DIAGNOSTICS = Boolean.getBoolean("ipl.portalDiagnostics");

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-shader-uniform-probe");

    @Unique
    private static final Set<String> IPL$PROBED = ConcurrentHashMap.newKeySet();

    /**
     * Registers the freshly linked program for {@link IplProgramBindHook}. With
     * {@code -Dipl.portalDiagnostics=true} it also logs, once per shader that IP transforms,
     * the link status and the active uniforms: whether the linker kept
     * {@code iportal_ClippingEquation} and {@code ipl_subLevelClipEquation}.
     */
    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("RETURN")
    )
    private void ipl$registerProgram(CallbackInfo ci) {
        int program = ((Shader) (Object) this).getId();
        IplProgramBindHook.forgetProgram(program);
        IplProgramRegistry.register(program, name);
        if (IPL$DIAGNOSTICS && ShaderCodeTransformation.shouldAddUniform(name) && IPL$PROBED.add(name)) {
            ipl$logActiveUniforms(program);
        }
    }

    @Unique
    private void ipl$logActiveUniforms(int program) {
        if (program == 0) {
            IPL$LOG.warn("[IPL-PROBE] name='{}' programId=0 -- can't enumerate, link likely failed", name);
            return;
        }

        IntBuffer statusBuf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(program, GL20.GL_LINK_STATUS, statusBuf);
        int linkStatus = statusBuf.get(0);

        IntBuffer countBuf = BufferUtils.createIntBuffer(1);
        GL20.glGetProgramiv(program, GL20.GL_ACTIVE_UNIFORMS, countBuf);
        int activeUniformCount = countBuf.get(0);

        String programLog = GL20.glGetProgramInfoLog(program, 4096);

        List<String> uniformNames = new ArrayList<>();
        boolean clipPresent = false;
        boolean subLevelClipPresent = false;
        IntBuffer sizeBuf = BufferUtils.createIntBuffer(1);
        IntBuffer typeBuf = BufferUtils.createIntBuffer(1);
        for (int i = 0; i < activeUniformCount; i++) {
            String uName = GL20.glGetActiveUniform(program, i, sizeBuf, typeBuf);
            int loc = GL20.glGetUniformLocation(program, uName);
            uniformNames.add(uName + "@" + loc + ":t=" + typeBuf.get(0) + ":s=" + sizeBuf.get(0));
            if ("iportal_ClippingEquation".equals(uName)) clipPresent = true;
            // GL reports a uniform array by its first element, "ipl_subLevelClipEquation[0]".
            if (uName.startsWith("ipl_subLevelClipEquation")) subLevelClipPresent = true;
        }

        IPL$LOG.info(
            "[IPL-PROBE] name='{}' linkStatus={} activeUniforms={} iportalClip={} subLevelClip={}{}",
            name,
            linkStatus == GL20.GL_TRUE ? "OK" : ("FAIL(" + linkStatus + ")"),
            activeUniformCount,
            clipPresent,
            subLevelClipPresent,
            programLog.isEmpty() ? "" : "\n----- PROGRAM INFO LOG -----\n" + programLog.trim() + "\n----- END LOG -----"
        );
        IPL$LOG.info("[IPL-PROBE] name='{}' uniforms={}", name, uniformNames);
    }
}
