package foundry.veil.mixin.network;

import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MinecraftServer.class)
public abstract class NetworkMinecraftServerMixin implements VeilPacketManager.PacketSink {

    @Shadow
    public abstract PlayerList getPlayerList();

    @Override
    public void sendPacket(Packet<?> packet) {
        this.getPlayerList().broadcastAll(packet);
    }
}
