package foundry.veil.impl.network;

import foundry.veil.Veil;
import foundry.veil.api.network.VeilPacketManager;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class VeilPacketSender {

    private static final VeilPacketManager PLAY = VeilPacketManager.create(Veil.MODID, "1");

    private VeilPacketSender(){
    }

    public static void init() {
        PLAY.registerClientbound(ClientboundAddPostProcessingPacket.TYPE, ClientboundAddPostProcessingPacket.CODEC, (payload, ctx) -> VeilClientServerFlags.addPipeline(payload.priority(), payload.pipeline()), true);
        PLAY.registerClientbound(ClientboundClearPostProcessingPacket.TYPE, ClientboundClearPostProcessingPacket.CODEC, (payload, ctx) -> VeilClientServerFlags.clearPipelines(), true);
        PLAY.registerClientbound(ClientboundRemovePostProcessingPacket.TYPE, ClientboundRemovePostProcessingPacket.CODEC, (payload, ctx) -> VeilClientServerFlags.removePipeline(payload.pipeline()), true);
    }
}
