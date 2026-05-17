package ipl.sable.mixin.debug;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.UUID;

/**
 * <b>Temporary debug-only mixin.</b> Comprehensively instruments Sable's
 * {@code SubLevelTrackingSystem} so we can capture exactly what happens to sub-level
 * tracking when a player crosses a portal and looks back through it.
 *
 * <p><b>Specific question we're trying to answer:</b> when a player traverses a portal
 * and we attempt cross-dim sub-level tracking (so the airship continues to render with
 * live motion while viewed from the destination dim), the airship turns invisible. With
 * repeated traversals the invisibility persists even when viewing from the source dim.
 * We need to know:
 * <ul>
 *   <li>When does the source-dim's {@code tick()} emit a {@code sendRemoval} for the
 *       traversing player? Once? Repeatedly?</li>
 *   <li>Is the tracking set ever populated with a cross-dim player (UUID present even
 *       though {@code level.getPlayerByUUID(uuid) == null})?</li>
 *   <li>When the player returns, does {@code sendFullSync} get called -- and does the
 *       client-side dedupe (added in {@link ipl.sable.mixin.SableStartTrackingDedupeMixin})
 *       end up cancelling everything?</li>
 *   <li>While the player is in the destination dim, does the source-dim tracking system
 *       send any updates (bounds/movement) that get routed to the wrong client container
 *       because they aren't tagged with PacketRedirection?</li>
 * </ul>
 *
 * <p>Tag prefix: {@code [IPL-SABLE-DIAG]}. Grep for that in {@code latest.log}.
 *
 * <p><b>To be removed once the root cause is identified and the cross-dim fix lands.</b>
 *
 * <p>Notes on placement:
 * <ul>
 *   <li>{@code tick HEAD/TAIL}: surrounds per-tick activity, marks frame boundaries</li>
 *   <li>{@code sendFullSync HEAD}: every StartTracking emission with full stack</li>
 *   <li>{@code sendBoundsUpdates HEAD}: bounds-update batch emission start</li>
 *   <li>{@code sendMovementUpdates HEAD}: movement-update batch emission start</li>
 *   <li>{@code sendRemoval HEAD}: every StopTracking emission with target + stack</li>
 * </ul>
 *
 * <p>{@code require = 0} on every @Inject so the mixin is best-effort -- if any one
 * injection fails to bind (Sable refactors method signatures), the rest still apply.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem", remap = false)
public abstract class SableTrackingDiagnosticMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-diag");

    @Shadow @Final private ServerLevel level;

    // ------------------------------------------------------------------------
    // tick() entry/exit -- frame boundaries
    // ------------------------------------------------------------------------

    @Inject(method = "tick", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$diagTickHead(SubLevelContainer container, CallbackInfo ci) {
        int subLevelCount = 0;
        for (SubLevel sl : container.getAllSubLevels()) {
            if (!sl.isRemoved()) subLevelCount++;
        }
        int playerCount = this.level.players().size();
        LOG.info(
            "[IPL-SABLE-DIAG] >>> tick BEGIN  dim={}  subLevels={}  playersInDim={}",
            this.level.dimension().location(),
            subLevelCount,
            playerCount
        );

        // For each non-removed sub-level, dump its tracking set so we can see who Sable
        // thinks owns the sub-level entering this tick.
        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            ServerSubLevel ssl = (ServerSubLevel) sl;
            Collection<UUID> tracking = ssl.getTrackingPlayers();
            LOG.info(
                "[IPL-SABLE-DIAG]   subLevel={} plotPos={} pose={} trackingSize={} tracking={}",
                ssl.getUniqueId(),
                ssl.getPlot().plotPos,
                ssl.logicalPose().position(),
                tracking.size(),
                tracking
            );
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$diagTickTail(SubLevelContainer container, CallbackInfo ci) {
        LOG.info(
            "[IPL-SABLE-DIAG] <<< tick END    dim={}",
            this.level.dimension().location()
        );
    }

    // ------------------------------------------------------------------------
    // sendFullSync -- StartTracking emission
    // ------------------------------------------------------------------------

    @Inject(method = "sendFullSync", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$diagSendFullSync(
        ServerPlayer player,
        ServerSubLevel subLevel,
        CustomPacketPayload extraPacket,
        CallbackInfo ci
    ) {
        LOG.info(
            "[IPL-SABLE-DIAG] sendFullSync  player={}/{}  playerDim={}  subLevel={}  plotPos={}  sourceDim={}  extra={}",
            player.getName().getString(),
            player.getGameProfile().getId(),
            player.serverLevel().dimension().location(),
            subLevel.getUniqueId(),
            subLevel.getPlot().plotPos,
            this.level.dimension().location(),
            extraPacket == null ? "null" : extraPacket.type().id(),
            new Throwable("ipl-sable-diag fullsync stack")
        );
    }

    // ------------------------------------------------------------------------
    // sendRemoval -- StopTracking emission
    // ------------------------------------------------------------------------

    @Inject(method = "sendRemoval", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$diagSendRemoval(
        VeilPacketManager.PacketSink sink,
        ServerSubLevel subLevel,
        CallbackInfo ci
    ) {
        LOG.info(
            "[IPL-SABLE-DIAG] sendRemoval  subLevel={}  plotPos={}  sourceDim={}  sinkClass={}",
            subLevel.getUniqueId(),
            subLevel.getPlot().plotPos,
            this.level.dimension().location(),
            sink.getClass().getName(),
            new Throwable("ipl-sable-diag removal stack")
        );
    }

    // ------------------------------------------------------------------------
    // sendBoundsUpdates -- batch bounds emission
    // ------------------------------------------------------------------------

    @Inject(method = "sendBoundsUpdates", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$diagSendBoundsUpdates(SubLevelContainer container, CallbackInfo ci) {
        int count = 0;
        for (SubLevel sl : container.getAllSubLevels()) {
            if (!sl.isRemoved()) count++;
        }
        LOG.info(
            "[IPL-SABLE-DIAG] sendBoundsUpdates  dim={}  subLevels={}",
            this.level.dimension().location(),
            count
        );
    }

    // ------------------------------------------------------------------------
    // sendMovementUpdates -- batch movement emission, with per-sub-level tracking dump
    // ------------------------------------------------------------------------

    @Inject(method = "sendMovementUpdates", at = @At("HEAD"), remap = false, require = 0)
    private void ipl$diagSendMovementUpdates(SubLevelContainer container, CallbackInfo ci) {
        LOG.info(
            "[IPL-SABLE-DIAG] sendMovementUpdates BEGIN  dim={}",
            this.level.dimension().location()
        );
        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            ServerSubLevel ssl = (ServerSubLevel) sl;
            Collection<UUID> tracking = ssl.getTrackingPlayers();
            LOG.info(
                "[IPL-SABLE-DIAG]   movement subLevel={}  trackingSize={}  tracking={}",
                ssl.getUniqueId(),
                tracking.size(),
                tracking
            );
        }
    }
}
