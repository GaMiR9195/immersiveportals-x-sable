package foundry.veil.impl.network;

import foundry.veil.Veil;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record ClientboundRemovePostProcessingPacket(ResourceLocation pipeline) implements CustomPacketPayload {

    public static final StreamCodec<ByteBuf, ClientboundRemovePostProcessingPacket> CODEC = ResourceLocation.STREAM_CODEC
            .map(ClientboundRemovePostProcessingPacket::new, ClientboundRemovePostProcessingPacket::pipeline);
    public static final Type<ClientboundRemovePostProcessingPacket> TYPE = new Type<>(Veil.veilPath("remove_post_processing"));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
