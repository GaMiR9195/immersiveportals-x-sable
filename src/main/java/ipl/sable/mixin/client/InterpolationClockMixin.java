package ipl.sable.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Приём снимков поз через часы Sable (одни на все клиентские уровни, {@code IplSharedClock}).
 * Меняются только снимки корабля, чей флип в пределах измерения клиент ещё не применил
 * (отложенный handoff): снимки нового кадра переводятся обратно в кадр источника, чтобы буфер
 * не смешивал две системы координат.
 */
@Pseudo
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public abstract class InterpolationClockMixin {

    @ModifyVariable(method = "receiveSnapshot", at = @At("HEAD"), argsOnly = true, require = 1)
    private Pose3dc ipl$keepPendingSnapshotsInSourceFrame(
        Pose3dc pose, ClientSubLevel subLevel, int gameTick, Pose3dc originalPose, PacketReceiveMode receiveMode
    ) {
        return IplParentDimSync.mapPendingDestinationSnapshotBack(subLevel.getUniqueId(), gameTick, pose);
    }
}
