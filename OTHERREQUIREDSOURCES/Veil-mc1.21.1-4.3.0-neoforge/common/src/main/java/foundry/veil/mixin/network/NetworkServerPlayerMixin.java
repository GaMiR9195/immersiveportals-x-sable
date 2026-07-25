package foundry.veil.mixin.network;

import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayer.class)
public class NetworkServerPlayerMixin implements VeilPacketManager.PacketSink {

    @Shadow
    public ServerGamePacketListenerImpl connection;

    @Override
    public void sendPacket(Packet<?> packet) {
        this.connection.send(packet);
    }
}
