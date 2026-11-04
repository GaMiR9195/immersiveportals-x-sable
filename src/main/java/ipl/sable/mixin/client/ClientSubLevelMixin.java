package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplStraddleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клиентский саб-уровень: поза отрисовки (проекция за порталом, призрак перехода) и очистка
 * состояния перехода при удалении.
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class ClientSubLevelMixin {

    // ---- Поза отрисовки ----
    // Порядок: проекция (поза за порталом, пока рисуется её кусок) → призрак перехода → поза
    // Sable. Перегружены обе версии: версия без аргумента отдаёт кэш, не вызывая версию с тиком.

    @ModifyReturnValue(method = "renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;", at = @At("RETURN"))
    private Pose3dc ipl$overrideRenderPoseNoArg(Pose3dc original) {
        return ipl$renderPose(original);
    }

    @ModifyReturnValue(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;", at = @At("RETURN"))
    private Pose3dc ipl$overrideRenderPoseFloat(Pose3dc original) {
        return ipl$renderPose(original);
    }

    @Unique
    private Pose3dc ipl$renderPose(Pose3dc original) {
        Pose3dc override = IplStraddleRenderState.getPoseFor(this);
        if (override != null) return override;
        Pose3dc ghost = IplParentDimSync.ghostRenderPose((ClientSubLevel) (Object) this);
        return ghost != null ? ghost : original;
    }

    // ---- Удаление ----
    // A reallocated UUID must not inherit the old object's portal coordinate frame; a
    // cross-dimension successor shares the UUID, so per-body state survives its ghost.

    @Inject(method = "onRemove", at = @At("HEAD"), require = 1)
    private void ipl$forgetTransit(CallbackInfo ci) {
        IplParentDimSync.onClientSubRemoved((ClientSubLevel) (Object) this);
    }
}
