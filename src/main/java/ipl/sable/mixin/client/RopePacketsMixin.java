package ipl.sable.mixin.client;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.network.packets.rope.ClientboundRopeDataPacket;
import dev.simulated_team.simulated.network.packets.rope.ClientboundRopeStoppedPacket;
import foundry.veil.api.network.handler.ClientPacketContext;
import ipl.sable.client.IplRopePacketRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Данные верёвки и «верёвка остановилась» — держателю в его собственном клиентском уровне,
 * а не в уровне игрока (подробности в {@link IplRopePacketRouting}).
 */
@Pseudo
@Mixin(targets = {
    "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeDataPacket",
    "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeStoppedPacket"
}, remap = false)
public abstract class RopePacketsMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$routeToOwningLevel(ClientPacketContext context, CallbackInfo ci) {
        Object self = this;
        if (self instanceof ClientboundRopeDataPacket packet) {
            RopeStrandHolderBehavior holder =
                IplRopePacketRouting.foreignHolder(context.player(), packet.ownerPos(), packet.uuid());
            if (holder == null) return;
            holder.receiveClientStrand(packet.interpolationTick(), packet.points(), packet.uuid(),
                packet.startAttachmentPos(), packet.endAttachmentPos());
            ci.cancel();
        } else if (self instanceof ClientboundRopeStoppedPacket packet) {
            RopeStrandHolderBehavior holder =
                IplRopePacketRouting.foreignHolder(context.player(), packet.ownerPos(), null);
            if (holder == null) return;
            holder.receiveClientStrandStopped();
            ci.cancel();
        }
    }
}
