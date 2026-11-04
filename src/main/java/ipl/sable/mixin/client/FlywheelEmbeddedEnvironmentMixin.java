package ipl.sable.mixin.client;

import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import ipl.sable.render.IplFlywheelFallback;
import ipl.sable.render.IplProgramBindHook;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Встроенные окружения Flywheel, которыми Sable рисует визуалы саб-уровней (шестерни и валы
 * Create через {@code BlockEntityStorageMixin.sable$createVisual}).
 *
 * <p>{@link EmbeddedEnvironment#setupDraw(GlProgram)} привязывает программу собственным
 * {@code GlProgram.bind} → {@code GL20C.glUseProgram}, минуя хуки {@code GlStateManager} и
 * {@code ProgramManager}, поэтому уравнения отсечения сюда передаются отдельно. Встройки
 * саб-уровней активны даже тогда, когда основной backend Flywheel выключен (Iris с шейдерпаком).
 */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public class FlywheelEmbeddedEnvironmentMixin {

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
    @Inject(method = "flush(J)V", at = @At("TAIL"))
    private void ipl$collapseHiddenEnvironment(long ptr, CallbackInfo ci) {
        for (EmbeddedEnvironment env = (EmbeddedEnvironment) (Object) this; env != null;
             env = ((FlywheelEmbeddedEnvironmentMixin) (Object) env).parent) {
            if (IplFlywheelFallback.isHiddenEnvironment(env)) {
                this.poseComposed.zero().m33(1.0f);
                MemoryUtil.memSet(ptr, 0, 64);
                // Столбцовый порядок (как ExtraMemoryOps.putMatrix4f): m33 — последний float.
                MemoryUtil.memPutFloat(ptr + 60L, 1.0f);
                return;
            }
        }
    }

    /** Уравнения отсечения — только для этой программы Flywheel, тем же путём, что и обычный bind. */
    @Inject(method = "setupDraw", at = @At("HEAD"))
    private void ipl$onSetupDraw(GlProgram program, CallbackInfo ci) {
        IplProgramBindHook.onBind(program.handle());
    }
}
