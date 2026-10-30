package ipl.sable.mixin.client;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.network.packets.rope.ClientboundRopeStoppedPacket;
import foundry.veil.api.network.handler.ClientPacketContext;
import ipl.sable.client.IplRopePacketRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Верёвка остановилась» — тому же держателю, что и данные (см. {@link IplRopePacketRouting}). */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeStoppedPacket", remap = false)
public abstract class IplRopeStoppedPacketRoutingMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$routeToOwningLevel(ClientPacketContext context, CallbackInfo ci) {
        ClientboundRopeStoppedPacket packet = (ClientboundRopeStoppedPacket) (Object) this;
        RopeStrandHolderBehavior holder =
            IplRopePacketRouting.foreignHolder(context.player(), packet.ownerPos(), null);
        if (holder == null) return;
        holder.receiveClientStrandStopped();
        ci.cancel();
    }
}
