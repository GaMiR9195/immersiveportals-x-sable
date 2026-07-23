package ipl.sable.duck;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Re-emit a full sub-level sync to one already-tracking player — used after a hosted
 * transit's parent flip to rebind the client's chunk/BE objects to the new dimension.
 * Implemented on {@code SubLevelTrackingSystem} by
 * {@link ipl.sable.mixin.SableCrossDimTrackingMixin}.
 */
public interface IplTrackingResyncDuck {

    void ipl$resendFullSync(ServerPlayer player, ServerSubLevel subLevel);
}
