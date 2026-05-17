package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import ipl.sable.iface.IplKinematicSubLevelHolder;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip {@link SubLevelHoldingChunkMap#moveToUnloaded} for kinematic mirrors.
 *
 * <p><b>Why this exists:</b> Sable's {@code PhysicsChunkTicketManager.update}
 * runs every physics tick. For each {@code ArbitraryPhysicsObject}, it checks
 * which chunks the object overlaps in world space; for any chunk that isn't
 * "loaded enough" (no vanilla chunk ticket on it), it invokes
 * {@code ArbitraryPhysicsObject.onUnloaded -> moveToUnloaded}, which
 * serialises the sub-level into a holding chunk and removes it from the
 * container.
 *
 * <p>For our kinematic mirrors, the world-space pose lives in the destination
 * dim near a portal that no player is necessarily standing on -- so the
 * underlying world chunks have no ticket and Sable thinks the mirror needs to
 * be unloaded. The user's last test log captured this: a mirror at plot
 * {@code (0,1)} in the nether being moved-to-unloaded thousands of times per
 * millisecond.
 *
 * <p>Cancelling at the {@code moveToUnloaded} HEAD short-circuits both the
 * serialise step and the downstream {@code removeSubLevel} call. The
 * surrounding loop's {@code iterator.remove()} on {@code arbitraryObjects}
 * still runs (it happens after the call site), but that's exactly what we
 * want -- removing the mirror from the physics-system's per-object iteration
 * means {@code update} won't re-fire this code path on the same mirror next
 * tick. We don't need Sable to physics-simulate mirrors; the controller pins
 * their pose externally each tick.
 *
 * <p><b>Backstop:</b> {@link SableMirrorRemovalGuardMixin} still blocks
 * unauthorised {@code removeSubLevel} calls from any path we haven't yet
 * identified. With this mixin in place, that backstop's counter should stay
 * near zero in steady state -- if it spikes, there's another removal path we
 * need to find.
 */
@Pseudo
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SableMirrorSkipMoveToUnloadedMixin {

    @Inject(
        method = "moveToUnloaded",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipl$skipKinematicMoveToUnloaded(
        ServerSubLevel sub, ChunkPos pos, CallbackInfo ci
    ) {
        if (sub instanceof IplKinematicSubLevelHolder holder
            && holder.ipl$isKinematicMirror()) {
            ci.cancel();
        }
    }
}
