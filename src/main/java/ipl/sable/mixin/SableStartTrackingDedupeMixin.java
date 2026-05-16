package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Make Sable's {@code ClientboundStartTrackingSubLevelPacket.handle} idempotent.
 *
 * <p>Upstream Sable's handler calls {@code clientContainer.allocateSubLevel(uuid, x, z, pose)}
 * unconditionally, and {@code SubLevelContainer.allocateSubLevel} throws
 * {@code IllegalArgumentException("Plot already exists at x, z")} if the plot slot is
 * occupied. So if the client receives StartTracking for a sub-level it's already tracking
 * -- which happens legitimately whenever a player goes out of range and comes back, or
 * crosses a portal and returns -- the client crashes.
 *
 * <p><b>Concrete trigger we hit:</b> portal traversal with an assembled airship. Player
 * crosses portal -> server emits StopTracking -> client should clear the sub-level. But
 * IP's portal-view rendering retains source-dim chunk/entity data for the duration of the
 * portal view (so you can see through the portal back into the source dim). When the
 * player re-enters the source dim, the server legitimately re-emits StartTracking. Plot
 * (0, 0) is still occupied client-side -> crash.
 *
 * <p>This mixin checks {@code container.getSubLevel(uuid)} before letting the handler
 * proceed. If the UUID is already tracked, cancel the handler -- the data being delivered
 * in the second StartTracking is essentially the same as the first (Sable's server
 * doesn't generate new state for a re-sync), so no client-side state needs updating.
 *
 * <p>This is a Sable robustness gap that would benefit any user, even without IP; the
 * mixin can stay even if upstream Sable later fixes it.
 *
 * <p>Diagnosis via {@code ipl.sable.mixin.debug.SableTrackingDebugMixin} confirmed both
 * emits come from the same source: {@code SubLevelTrackingSystem.tick() line 219}, the
 * "add players who SHOULD be tracking but aren't" branch. Server behavior is correct;
 * the gap is purely client-side.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket", remap = false)
public abstract class SableStartTrackingDedupeMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable");

    /**
     * Record accessor for the sub-level UUID. Sable's packet is a {@code record} with
     * {@code UUID subLevelID()} as the synthetic accessor; @Shadow binds to it by name.
     */
    @Shadow public abstract UUID subLevelID();

    @Inject(
        method = "handle",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void ipl$dedupeStartTracking(PacketContext context, CallbackInfo ci) {
        Level level = context.level();
        if (level == null) return;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        UUID uuid = this.subLevelID();
        if (container.getSubLevel(uuid) != null) {
            // Already tracking this sub-level; skip re-allocation to avoid the
            // "Plot already exists" crash. The server's re-emit doesn't carry novel
            // state we need to apply -- pose/bounds updates come on separate packets.
            LOG.debug("[IPL-SABLE] Skipping duplicate StartTracking for sub-level {} (already tracked)", uuid);
            ci.cancel();
        }
    }
}
