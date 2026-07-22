package foundry.veil.impl.network;

import foundry.veil.Veil;
import foundry.veil.api.util.EnumCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record ClientboundAddPostProcessingPacket(int priority, ResourceLocation pipeline) implements CustomPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, ClientboundAddPostProcessingPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ClientboundAddPostProcessingPacket::priority,
            ResourceLocation.STREAM_CODEC,
            ClientboundAddPostProcessingPacket::pipeline,
            ClientboundAddPostProcessingPacket::new
    );
    public static final CustomPacketPayload.Type<ClientboundAddPostProcessingPacket> TYPE = new Type<>(Veil.veilPath("add_post_processing"));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
