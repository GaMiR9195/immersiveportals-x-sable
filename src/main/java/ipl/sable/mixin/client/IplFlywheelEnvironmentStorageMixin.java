package ipl.sable.mixin.client;

import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import ipl.sable.render.IplFlywheelFallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перед записью матриц всех встроенных окружений Flywheel решаем, какие из них схлопнуть в этом
 * проходе: окружения саб-уровней, пересекающих портал (их BE рисует BER-путь с плоскостями
 * отсечения), и скрытых «преемников» перехода. См. {@link IplFlywheelFallback}.
 */
@Mixin(value = EnvironmentStorage.class, remap = false)
public abstract class IplFlywheelEnvironmentStorageMixin {

    @Inject(method = "flush()V", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$selectHiddenEnvironments(CallbackInfo ci) {
        IplFlywheelFallback.beginEnvironmentFlush();
    }
}
