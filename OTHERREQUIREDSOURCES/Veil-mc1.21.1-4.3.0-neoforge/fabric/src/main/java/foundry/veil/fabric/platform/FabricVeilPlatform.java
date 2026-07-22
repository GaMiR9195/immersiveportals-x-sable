package foundry.veil.fabric.platform;

import foundry.veil.platform.VeilPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class FabricVeilPlatform implements VeilPlatform {

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.FABRIC;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean canAttachRenderdoc() {
        return true;
    }

    @Override
    public boolean hasErrors() {
        return false;
    }

    @Override
    public boolean hasChannel(PacketListener listener, CustomPacketPayload.Type<?> type) {
        return switch (listener.flow().getOpposite()) {
            case SERVERBOUND -> ClientPlayNetworking.canSend(type);
            case CLIENTBOUND -> listener instanceof ServerGamePacketListenerImpl impl &&
                    ServerPlayNetworking.canSend(impl, type);
        };
    }
}
