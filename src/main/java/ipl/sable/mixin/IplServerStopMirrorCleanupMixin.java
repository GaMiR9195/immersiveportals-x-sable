package ipl.sable.mixin;

import ipl.sable.transit.SableTransitController;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tear down all kinematic-mirror state at the HEAD of
 * {@link MinecraftServer#stopServer()} -- before the final world save and before
 * the chunk-unload drain.
 *
 * <p>Delegates to {@link SableTransitController#onServerStopping(MinecraftServer)},
 * which despawns every registered mirror (removing it from its dest container so
 * its plot chunks are freed) and clears all per-process static state
 * ({@code MirrorRegistry} + the cooldown maps).
 *
 * <p><b>Why HEAD of stopServer specifically:</b> at this point the levels and
 * sub-level containers are still fully alive (the save and the
 * {@code ServerChunkCache.tick -> ChunkMap.processUnloads} drain run later in the
 * method), so {@code despawnMirror}'s {@code removeSubLevel} + cross-dim
 * StopTracking work normally. Removing the mirrors here means:
 * <ul>
 *   <li>their plot chunks are freed before the drain, so the drain doesn't spin
 *       waiting on chunks a lingering mirror keeps occupied (the 31May shutdown
 *       hang -- watchdog stuck in vanilla {@code processUnloads});</li>
 *   <li>no mirror is in any container at save time (defence-in-depth alongside
 *       the storage chokepoint);</li>
 *   <li>the static registry is empty for the next integrated server in this JVM,
 *       so a quit-to-title + re-enter can't carry a stale mirror forward (the
 *       "3 airships" duplication).</li>
 * </ul>
 *
 * <p>Uses {@code MinecraftServer} as the target (same as
 * {@link IplServerHeartbeatMixin}) rather than a NeoForge {@code ServerStoppingEvent}
 * listener, because this fork wires server lifecycle through mixins and there's no
 * common {@code ipl.sable} server-init entry point to register a listener from.
 */
@Mixin(MinecraftServer.class)
public abstract class IplServerStopMirrorCleanupMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void ipl$cleanupMirrorsOnStop(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        try {
            SableTransitController.onServerStopping(server);
        } catch (Throwable t) {
            // Never let cleanup abort shutdown.
            org.slf4j.LoggerFactory.getLogger("ipl-sable-transit")
                .warn("[IPL-MIRROR] server-stop cleanup threw", t);
        }
    }
}
