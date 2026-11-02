package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplDimSwitchGuard;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplStraddleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клиентский саб-уровень: поза отрисовки (проекция, призрак перехода, непрерывность после смены
 * измерения), небесный свет после смены измерения и очистка состояния перехода при удалении.
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class ClientSubLevelMixin {

    /** Показанная поза и свет этого объекта для {@link IplDimSwitchGuard}. */
    @Unique private final IplDimSwitchGuard.Track ipl$switchTrack = new IplDimSwitchGuard.Track();

    // ---- Поза отрисовки ----
    // Порядок: проекция (поза за порталом, пока рисуется её кусок) → призрак перехода → поза
    // Sable, сглаженная в окне после смены измерения. Перегружены обе версии: версия без
    // аргумента отдаёт кэш, не вызывая версию с тиком.

    @ModifyReturnValue(method = "renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;",
        at = @At("RETURN"), require = 0)
    private Pose3dc ipl$overrideRenderPoseNoArg(Pose3dc original) {
        return ipl$renderPose(original, Float.NaN);
    }

    @ModifyReturnValue(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
        at = @At("RETURN"), require = 0)
    private Pose3dc ipl$overrideRenderPoseFloat(Pose3dc original, float partialTick) {
        return ipl$renderPose(original, partialTick);
    }

    @Unique
    private Pose3dc ipl$renderPose(Pose3dc original, float partialTick) {
        Pose3dc override = IplStraddleRenderState.getPoseFor(this);
        if (override != null) return override;
        ClientSubLevel self = (ClientSubLevel) (Object) this;
        Pose3dc ghost = IplParentDimSync.ghostRenderPose(self);
        if (ghost != null) return ghost;
        return IplDimSwitchGuard.smooth(self, ipl$switchTrack, original, partialTick);
    }

    // ---- Небесный свет: без провала сразу после смены измерения ----

    @ModifyReturnValue(method = "getLatestSkyLightScale", at = @At("RETURN"), require = 0)
    private int ipl$holdSkyLight(int original) {
        return IplDimSwitchGuard.holdSkyLight(ipl$switchTrack, original);
    }

    // ---- Удаление: забыть кадр перехода ----
    // A reallocated UUID must not inherit the old object's portal coordinate frame.

    @Inject(method = "onRemove", at = @At("HEAD"), remap = false, require = 1)
    private void ipl$forgetTransit(CallbackInfo ci) {
        // A cross-dimension successor shares the UUID: per-body state survives its ghost.
        IplParentDimSync.onClientSubRemoved((ClientSubLevel) (Object) this);
    }
}
