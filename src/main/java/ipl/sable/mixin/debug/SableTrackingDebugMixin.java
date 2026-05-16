package ipl.sable.mixin.debug;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>Temporary debug-only mixin.</b> Instruments Sable's
 * {@code SubLevelTrackingSystem.sendFullSync} -- the emission point of
 * {@code ClientboundStartTrackingSubLevelPacket} -- to log every call with the sub-level
 * UUID, plot position, target player, and a full stack trace.
 *
 * <p>Purpose: diagnose the "Plot already exists at 0, 0" client crash that fires after
 * portal traversal with an assembled airship. The crash happens because the server emits
 * StartTracking twice for the same sub-level in close succession; Sable's client doesn't
 * dedupe and allocateSubLevel throws on the second attempt. We need to know which call
 * stack triggers the duplicate to fix the root cause.
 *
 * <p>Read the log after reproducing: grep for {@code [IPL-SABLE-DEBUG]}. Two consecutive
 * emits with the same {@code subLevel=<uuid>} are the duplicate -- compare the stack
 * traces to find which divergent path triggered the second.
 *
 * <p><b>Remove this mixin once the duplicate emission's source is identified and fixed.</b>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem", remap = false)
public abstract class SableTrackingDebugMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-debug");

    @Inject(
        method = "sendFullSync",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void ipl$logSendFullSync(
        ServerPlayer player,
        ServerSubLevel subLevel,
        CustomPacketPayload extraPacket,
        CallbackInfo ci
    ) {
        LOG.info(
            "[IPL-SABLE-DEBUG] sendFullSync(player={}, subLevel={}, plotPos={}, dim={})",
            player.getName().getString(),
            subLevel.getUniqueId(),
            subLevel.getPlot().plotPos,
            player.serverLevel().dimension().location(),
            new Throwable("ipl-sable-debug stack")
        );
    }
}
