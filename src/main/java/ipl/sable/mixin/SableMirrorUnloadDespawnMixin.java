package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import ipl.sable.transit.MirrorOps;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Despawn (don't serialize) a kinematic mirror when Sable tries to unload it.
 *
 * <p><b>Why:</b> Sable's {@code PhysicsChunkTicketManager.update} runs every
 * physics tick. For each sub-level whose world-space AABB overlaps a chunk that
 * isn't "loaded enough" (no vanilla chunk ticket), it calls
 * {@code holdingChunkMap.moveToUnloaded(subLevel, pos)} and then -- assuming that
 * call removed the sub-level from the container -- does {@code i--; continue} to
 * re-scan the shrunken list. Our mirrors live in dest-dim chunks near a portal
 * that often has no ticket, so {@code moveToUnloaded} fires on them constantly.
 *
 * <p><b>The hang we're fixing:</b> the previous version of this mixin
 * <em>cancelled</em> {@code moveToUnloaded} at HEAD for mirrors. That left the
 * mirror in {@code getAllSubLevels()}, so the loop's {@code i--; continue}
 * re-processed the same index against the same unloaded chunk forever -- a silent
 * infinite-loop server hang (watchdog 31May froze at
 * {@code PhysicsChunkTicketManager.update:135}). Cancelling was masking the loop's
 * removal contract, not honouring it.
 *
 * <p><b>The fix:</b> instead of cancelling, actually despawn the mirror
 * ({@link MirrorOps#despawnMirrorOnUnload}) -- a plain container removal that
 * shrinks {@code getAllSubLevels()} (so {@code i--; continue} works and the loop
 * advances), keeps it off disk (no holding-chunk serialize), emits clean
 * cross-dim StopTracking, and clears the registry so the controller respawns a
 * fresh mirror when the player next approaches. THEN cancel the original
 * {@code moveToUnloaded} so it doesn't also try to serialize/holding-remove the
 * (now-gone) mirror.
 *
 * <p>Replaces {@code SableMirrorSkipMoveToUnloadedMixin}.
 */
@Pseudo
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SableMirrorUnloadDespawnMixin {

    @Inject(
        method = "moveToUnloaded",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$despawnKinematicOnUnload(
        ServerSubLevel sub, ChunkPos pos, CallbackInfo ci
    ) {
        if (sub instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror()) {
            MirrorOps.despawnMirrorOnUnload(sub);
            ci.cancel();
        }
    }
}
