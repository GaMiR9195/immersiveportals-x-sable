package ipl.sable.mixin.client;

import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import ipl.sable.render.IplFlywheelFallback;
import ipl.sable.render.IplProgramBindHook;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Catch Flywheel's per-embedded-environment program binds that miss BOTH
 * our {@code GlStateManager._glUseProgram} AND
 * {@code ProgramManager.glUseProgram} hooks.
 *
 * <p><b>The path we're catching:</b> Sable wires Create
 * {@code BracketedKineticBlockEntity} (cogs) to Flywheel
 * {@code BlockEntityVisualizer} via
 * {@code BlockEntityStorageMixin.sable$createVisual}, embedding the cog
 * into a per-sub-level Flywheel {@code VisualEmbedding}. The cog is
 * rendered by {@link EmbeddedEnvironment#setupDraw(GlProgram)} which
 * calls Flywheel's own {@code GlProgram.bind} ->
 * {@code GL20C.glUseProgram} directly, bypassing all Mojang/Iris
 * wrappers.
 *
 * <p>Critical insight (user-reported): Flywheel's MAIN backend may have
 * fallen back to "off" globally under Iris+shaderpack, but per-sub-level
 * Flywheel embeddings stay active -- that's the whole point of Sable's
 * BlockEntityStorageMixin wiring. Empirical: sub-level cogs cast no
 * shadows (Flywheel path), main-world cogs DO cast shadows (vanilla BER
 * path). So `EmbeddedEnvironment.setupDraw` IS firing for sub-level
 * cogs in this config.
 *
 * <p>An earlier @Pseudo+targets-string attempt silently no-op'd --
 * mixin without compile-time class info couldn't bind the inject. This
 * version uses a direct class reference (compileOnly Flywheel dep added
 * in allProjects.gradle).
 *
 * <p>Registers Flywheel-owned program handles with the normal bind hook.
 * Clipping remains scoped to Sable's individual sub-level draw brackets.
 */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public class IplFlywheelEmbeddedClipMixin {

    @Shadow @Final private EmbeddedEnvironment parent;
    @Shadow @Final private Matrix4f poseComposed;

    /**
     * Схлопнуть окружение саб-уровня, чьи BE сейчас рисует BER-путь (пересечение портала,
     * скрытый преемник перехода) — см. {@link IplFlywheelFallback}. Матрица модели «всё в одну
     * точку» (нулевая, кроме w = 1) превращает каждый треугольник в вырожденный — растеризатор
     * его не рисует: instancing читает {@code poseComposed} в {@code setupDraw}, indirect —
     * буфер матриц, куда {@code flush} только что её записал. Вложенные окружения (родитель
     * скрыт) схлопываются тоже. {@code flush} пересчитывает матрицу каждый кадр, так что
     * окружение возвращается само, как только саб-уровень отходит от портала.
     */
    @Inject(method = "flush(J)V", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$collapseHiddenEnvironment(long ptr, CallbackInfo ci) {
        EmbeddedEnvironment env = (EmbeddedEnvironment) (Object) this;
        for (int depth = 0; env != null && depth < 16; depth++) {
            if (IplFlywheelFallback.isHiddenEnvironment(env)) {
                this.poseComposed.zero().m33(1.0f);
                MemoryUtil.memSet(ptr, 0, 64);
                // Столбцовый порядок (как ExtraMemoryOps.putMatrix4f): m33 — последний float.
                MemoryUtil.memPutFloat(ptr + 60L, 1.0f);
                return;
            }
            env = ((IplFlywheelEmbeddedClipMixin) (Object) env).parent;
        }
    }

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-flywheel-embedded-clip");

    @Unique
    private static final AtomicBoolean IPL$LOGGED_FIRST_FIRE = new AtomicBoolean(false);

    @Inject(
        method = "setupDraw",
        at = @At("HEAD"),
        remap = false
    )
    private void ipl$onSetupDraw(GlProgram program, CallbackInfo ci) {
        if (IPL$LOGGED_FIRST_FIRE.compareAndSet(false, true)) {
            IPL$LOG.debug("[IPL-FLYWHEEL-EMBED-FIRED] FIRST-FIRE program={} class={}",
                program, program != null ? program.getClass().getName() : "null");
        }

        if (program == null) return;
        int progId = program.handle();
        if (progId <= 0) return;

        // Update only this Flywheel-owned program, using the same scoped bind path.
        IplProgramBindHook.onBind(progId);

        // Flywheel draws after Sable's scoped bracket. Reusing the last projection
        // equation here makes source and destination projections clip each other.
    }
}
