package ipl.sable.mixin;

import dev.simulated_team.simulated.network.packets.rope.ClientboundRopeDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY DIAGNOSTIC — [IPL-ROPE] client packet arrival counter.
 *
 * <p>Paired with {@code IplRopeDiagMixin}'s {@code receiveClientStrand} probe: an arrival
 * log WITHOUT a matching receive log means the handler failed to resolve the rope holder
 * block entity at the packet's owner position (the client plot bridge / chunk-cache path),
 * which is exactly the link that cannot be proven statically. Remove once verified.
 */
@Pseudo
@Mixin(value = ClientboundRopeDataPacket.class, remap = false)
public abstract class IplRopePacketDiagMixin {

    @Unique
    private static long ipl$lastArriveLogMs = 0;

    @Inject(method = "handle", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$logPacketArrival(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - ipl$lastArriveLogMs < 2000) return;
        ipl$lastArriveLogMs = now;
        org.slf4j.LoggerFactory.getLogger("ipl-rope").info(
            "[IPL-ROPE] client rope data packet arrived (watch for a matching 'client received' line)");
    }
}
