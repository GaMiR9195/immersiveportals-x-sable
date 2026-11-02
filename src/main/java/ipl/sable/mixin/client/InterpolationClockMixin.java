package ipl.sable.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplDimSwitchGuard;
import ipl.sable.client.IplParentDimSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Часы интерполяции Sable (по одним на клиентский уровень) — всё, что IPL в них трогает.
 *
 * <ol>
 *   <li><b>Снимки ожидающей передачи.</b> Пока визуальная передача корабля в другое измерение не
 *   подтверждена, снимки назначения переводятся обратно в кадр источника: буфер не смешивает две
 *   системы координат.</li>
 *   <li><b>Только наблюдение.</b> Ход часов — как в Sable. Русский комментарий (почему): в 0.6.0
 *   r3 здесь сглаживался темп сервера и нижняя граница окна, но по коду при входе в измерение
 *   часы не прыгают, а правка меняла поведение Sable всегда. В окне после смены измерения
 *   {@link IplDimSwitchGuard} только записывает необычные шаги и сбросы часов в итог
 *   {@code [IPL-DIM]}.</li>
 * </ol>
 */
@Pseudo
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public abstract class InterpolationClockMixin {

    @Shadow private PacketReceiveMode receivingMode;
    @Shadow private double mostRecentTick;
    @Shadow private boolean receivedFirstUpdate;
    @Shadow private boolean stopped;

    @Inject(method = "receiveSnapshot", at = @At("HEAD"), cancellable = true, remap = false,
        require = 1)
    private void ipl$keepPendingSnapshotsInSourceFrame(
        ClientSubLevel subLevel, int gameTick, Pose3dc pose, PacketReceiveMode receiveMode,
        CallbackInfo ci
    ) {
        Pose3dc sourceFramePose = IplParentDimSync.mapPendingDestinationSnapshotBack(
            subLevel.getUniqueId(), gameTick, pose);
        if (sourceFramePose == pose) return;
        this.receivingMode = receiveMode;
        subLevel.getInterpolator().receiveSnapshot(gameTick, sourceFramePose);
        ci.cancel();
    }

    @Inject(method = "receiveInfo", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$noteInfo(int msSinceLast, int gameTick, boolean stopped, CallbackInfo ci) {
        IplDimSwitchGuard.noteClockInfo((ClientSableInterpolationState) (Object) this, this.mostRecentTick,
            gameTick, !this.receivedFirstUpdate, this.stopped, stopped);
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$noteStep(CallbackInfo ci) {
        // Стоящие часы упираются в верхнюю границу и не шагают — это не аномалия.
        if (this.receivedFirstUpdate && !this.stopped) {
            IplDimSwitchGuard.noteClockStep((ClientSableInterpolationState) (Object) this);
        }
    }
}
