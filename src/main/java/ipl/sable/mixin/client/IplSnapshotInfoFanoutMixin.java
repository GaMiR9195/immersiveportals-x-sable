package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Fan the per-player interpolation-clock payload out to EVERY loaded client level's
 * {@link ClientSubLevelContainer}.
 *
 * <p>The snapshot-info payload carries no sub-level context — Sable applies it to the
 * container of the level it was delivered under (the player's current level). Ships now
 * live in their parent dimension's client container, so a ship watched through a portal
 * (or one the player just left behind by switching dimension) sits in a DIFFERENT
 * container whose interpolation clock would otherwise starve: snapshots keep arriving
 * per-sub (cross-level lookup bridge) but the clamp window and tick-rate estimate never
 * advance, freezing the rendered pose. One clock feed per container keeps every
 * dimension's ships interpolating.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket", remap = false)
public abstract class IplSnapshotInfoFanoutMixin {

    @Shadow @Final private int msSinceLast;
    @Shadow @Final private int gameTick;
    @Shadow @Final private boolean stopped;

    @Inject(method = "handleClient(Lnet/minecraft/world/level/Level;)V", at = @At("RETURN"), remap = false, require = 0)
    private void ipl$fanInfoToAllClientContainers(Level level, CallbackInfo ci) {
        if (level == null || !level.isClientSide() || !ClientWorldLoader.getIsInitialized()) {
            return;
        }
        for (ClientLevel other : ClientWorldLoader.getClientWorlds()) {
            if (other == level) continue;
            SubLevelContainer container = SubLevelContainer.getContainer((Level) other);
            if (container instanceof ClientSubLevelContainer clientContainer) {
                clientContainer.getInterpolation().receiveInfo(this.msSinceLast, this.gameTick, this.stopped);
            }
        }
    }
}
