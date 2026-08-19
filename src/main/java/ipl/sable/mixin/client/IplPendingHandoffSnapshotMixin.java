package ipl.sable.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a crossing sub-level in ONE frame until its visual handoff commits.
 *
 * <p>The server rehomes a body the instant its collider passes the portal, but the client
 * renders ~6 ticks in the past, so for those ticks the visible body is still on the source
 * side while every arriving snapshot is already in destination coordinates. Interpolating
 * between the two charts is what drew the body flying the entire distance between the paired
 * portals, over and over.
 *
 * <p>The fix is to pick a canonical frame and never mix: while a handoff is queued, incoming
 * destination-frame snapshots are expressed BACK into the frame the client is currently
 * drawing in. Because the portal mapping is an isometry, the mapped image of the destination
 * trajectory is the exact continuation of the source one -- the body keeps its speed, sails
 * straight through the doorway and overshoots it just as the server did, which is precisely
 * the "it should look like a window" behaviour. {@code IplParentDimSync.applyHandoff} later
 * maps the whole retained timeline forward in one step.
 *
 * <p>Two deliberate details:
 *
 * <ul>
 *   <li>The packet's own pose is rewritten IN PLACE when it is mutable, so Sable's real
 *       {@code receiveSnapshot} still runs, including its {@code PacketReceiveMode} dispatch
 *       and its network-thread/main-thread handling. Cancelling and re-dispatching by hand
 *       would quietly bypass that. Mutating the packet pose is safe: Sable itself stores it
 *       by reference into the interpolator buffer, so it is not shared with anything else.</li>
 *   <li>Both the map-back and the commit synchronize on {@code interpolator.buffer}, so a
 *       snapshot can never be mapped for a frame the buffer has already left.</li>
 * </ul>
 */
@Pseudo
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public abstract class IplPendingHandoffSnapshotMixin {

    @Inject(method = "receiveSnapshot", at = @At("HEAD"), cancellable = true, remap = false,
        require = 0)
    private void ipl$keepPendingSnapshotsInSourceFrame(
        ClientSubLevel subLevel, int gameTick, Pose3dc pose, PacketReceiveMode receiveMode,
        CallbackInfo ci
    ) {
        if (subLevel == null || pose == null) return;
        SubLevelSnapshotInterpolator interpolator = subLevel.getInterpolator();
        if (interpolator == null) return;
        synchronized (interpolator.buffer) {
            Pose3dc sourceFramePose = IplParentDimSync.mapPendingDestinationSnapshotBack(
                subLevel.getUniqueId(), pose);
            if (sourceFramePose == pose) return;
            if (pose instanceof Pose3d mutable) {
                mutable.set(sourceFramePose);
                return;
            }
            interpolator.receiveSnapshot(gameTick, sourceFramePose);
            ci.cancel();
        }
    }
}
