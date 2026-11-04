package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import ipl.sable.client.IplSharedClock;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Clock packets of every level feed the one shared clock; {@link IplSharedClock#merge} decides
 * whether it runs, stops, restarts, resumes in phase or drops a late packet (TCP and UDP both
 * arrive through {@code handleClient}).
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket", remap = false)
public abstract class SnapshotInfoPacketMixin {

    @WrapOperation(
        method = "handleClient",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/client/ClientSableInterpolationState;receiveInfo(IIZ)V"
        ),
        require = 1
    )
    private void ipl$mergeLevelClocks(
        ClientSableInterpolationState clock, int msSinceLast, int gameTick, boolean stopped,
        Operation<Void> original, @Local(argsOnly = true) Level level
    ) {
        switch (IplSharedClock.merge(clock, level, gameTick, stopped)) {
            case RUNNING -> original.call(clock, msSinceLast, gameTick, false);
            case STOPPED -> original.call(clock, msSinceLast, gameTick, true);
            case RESTART -> {
                // Every level went quiet without a stop: restart the way Sable does after one.
                original.call(clock, msSinceLast, gameTick, true);
                original.call(clock, msSinceLast, gameTick, false);
            }
            case RESUME -> {
                // Cleared first, so Sable does not take this for a start after a stop.
                ((InterpolationClockAccessor) clock).ipl$setStopped(false);
                original.call(clock, msSinceLast, gameTick, false);
            }
            case LATE -> {
                // Older than the clock: Sable would drop it too.
            }
        }
    }
}
