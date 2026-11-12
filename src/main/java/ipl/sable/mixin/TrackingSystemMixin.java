package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import foundry.veil.api.network.VeilPacketManager;
import io.netty.channel.local.LocalAddress;
import ipl.sable.IplTimeline;
import ipl.sable.transit.IplAtlasStraddleSession;
import ipl.sable.transit.IplStraddlePoseMap;
import ipl.sable.transit.IplStraddleSessionSync;
import ipl.sable.transit.StraddleKey;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sub-level tracking that follows what Immersive Portals actually streams to each player.
 *
 * <p>Sable decides who tracks a sub-level by plain distance to players of the SAME level.
 * With portals that is wrong in both directions: a player in another level who sees the ship
 * through a portal (or stands next to one of its portal images) gets nothing, and the
 * distance between a ship in one level and a player in another is meaningless. A player that
 * lost tracking and regains it later gets a full re-sync, which is exactly the visible
 * "something reloads" when following a ship into its new dimension.
 *
 * <p>Rules applied here, once per tick:
 * <ul>
 *   <li><b>Viewers.</b> A player views a sub-level if IP has loaded any chunk under the
 *       ship's world bounds to them (from any level, through any portal) or if they stand
 *       near one of its portal images. Same-level players keep Sable's range on top of that;
 *       players of other levels track only by view.</li>
 *   <li><b>Remote viewers stay tracked.</b> Sable resolves trackers with
 *       {@link ServerLevel#getPlayerByUUID}, which only sees its own level; for remote
 *       viewers the server-wide player is returned instead, so neither the removal pass nor
 *       movement/bounds updates skip them. New remote viewers are bootstrapped with a full
 *       sync.</li>
 *   <li><b>Packets land in this level's client container.</b> Full-sync, bounds, movement
 *       and removal packets are tagged with this dimension ({@link PacketRedirection});
 *       movement goes over TCP for every player that sees another level and for a
 *       singleplayer host, because Sable's UDP path bypasses that tagging.</li>
 *   <li><b>One timeline.</b> Snapshots and clock packets of every level carry the server-wide
 *       stamp of {@link IplTimeline} instead of Sable's per-level count, so a client can run
 *       one interpolation clock for all levels it shows and a ship that changes level keeps
 *       its snapshot history.</li>
 * </ul>
 *
 * <p>{@code StartTrackingPacketMixin} still guards the client against a repeated
 * start-tracking for a sub-level it already holds.
 */
@Pseudo
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class TrackingSystemMixin {

    @Shadow @Final private ServerLevel level;

    @Shadow private int interpolationTick;

    @Shadow
    private void sendFullSync(ServerPlayer player, ServerSubLevel subLevel, CustomPacketPayload extraPacket) {
        throw new AssertionError();
    }

    @Shadow
    private void sendBoundsUpdates(SubLevelContainer container) {
        throw new AssertionError();
    }

    @Shadow
    private void sendMovementUpdates(SubLevelContainer container) {
        throw new AssertionError();
    }

    /** Chunks per axis searched for viewers; bounds wider than this are searched around the center. */
    @Unique
    private static final int IPL_MAX_VIEW_SPAN = 16;

    /** Per tick: sub-level UUID -> players viewing it (any level). */
    @Unique
    private final Map<UUID, Set<UUID>> ipl$viewersBySubLevel = new HashMap<>();

    /** Per tick: viewers that stand in ANOTHER level, over all sub-levels of this one. */
    @Unique
    private final Set<UUID> ipl$remoteViewers = new HashSet<>();

    // ------------------------------------------------------------------------
    // Viewers, computed once at tick HEAD
    // ------------------------------------------------------------------------

    /**
     * Start-tracking (sent from tick) and the movement updates stamp with this tick's
     * server-wide number; Sable's increment after the movement updates is overwritten here
     * next tick.
     */
    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void ipl$stampSharedTimeline(SubLevelContainer container, CallbackInfo ci) {
        interpolationTick = IplTimeline.stamp(level.getServer());
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void ipl$collectViewers(SubLevelContainer container, CallbackInfo ci) {
        ipl$viewersBySubLevel.clear();
        ipl$remoteViewers.clear();

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            Set<UUID> viewers = ipl$addChunkViewers(subLevel, null);
            viewers = ipl$addImageViewers(subLevel, viewers);
            if (viewers != null) ipl$viewersBySubLevel.put(subLevel.getUniqueId(), viewers);
        }
    }

    /** Players IP streams any chunk under the ship's world bounds to, from whatever level they are in. */
    @Unique
    private Set<UUID> ipl$addChunkViewers(SubLevel subLevel, Set<UUID> viewers) {
        Vector3dc center = subLevel.logicalPose().position();
        BoundingBox3dc bounds = subLevel.boundingBox();
        int centerX = Mth.floor(center.x()) >> 4;
        int centerZ = Mth.floor(center.z()) >> 4;
        int half = IPL_MAX_VIEW_SPAN / 2;
        // The pose center is always included, so a not-yet-computed box still finds the ship.
        int minX = Math.max(Math.min(Mth.floor(bounds.minX()) >> 4, centerX), centerX - half);
        int maxX = Math.min(Math.max(Mth.floor(bounds.maxX()) >> 4, centerX), centerX + half);
        int minZ = Math.max(Math.min(Mth.floor(bounds.minZ()) >> 4, centerZ), centerZ - half);
        int maxZ = Math.min(Math.max(Mth.floor(bounds.maxZ()) >> 4, centerZ), centerZ + half);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                var records = ImmPtlChunkTracking.getWatchRecordForChunk(level.dimension(), x, z);
                if (records == null) continue;
                for (ImmPtlChunkTracking.PlayerWatchRecord record : records.values()) {
                    if (record.isLoadedToPlayer) viewers = ipl$addViewer(viewers, record.player);
                }
            }
        }
        return viewers;
    }

    /**
     * Players of ANOTHER level standing near one of the ship's portal images (an active
     * straddle session into their level) see the ship as a projection: they need its live
     * state even when IP's portal chunk tracking doesn't cover the ship's own chunks.
     */
    @Unique
    private Set<UUID> ipl$addImageViewers(SubLevel subLevel, Set<UUID> viewers) {
        if (IplAtlasStraddleSession.isIdle()) return viewers;
        double range = SableConfig.SUB_LEVEL_TRACKING_RANGE.getAsDouble();
        double rangeSq = range * range;
        Vector3dc pos = subLevel.logicalPose().position();
        Vec3 source = new Vec3(pos.x(), pos.y(), pos.z());
        for (StraddleKey key : IplAtlasStraddleSession.sessionKeysFor(subLevel.getUniqueId())) {
            ServerLevel dest = IplAtlasStraddleSession.sessionDest(key);
            IplStraddlePoseMap.StraddleMapping mapping = IplAtlasStraddleSession.sessionMapping(key);
            if (dest == null || dest == level || mapping == null) continue;
            Vec3 image = mapping.mapPoint(source);
            for (ServerPlayer player : dest.players()) {
                if (image.distanceToSqr(player.position()) < rangeSq) viewers = ipl$addViewer(viewers, player);
            }
        }
        return viewers;
    }

    @Unique
    private Set<UUID> ipl$addViewer(Set<UUID> viewers, ServerPlayer player) {
        if (viewers == null) viewers = new HashSet<>();
        viewers.add(player.getUUID());
        if (player.serverLevel() != level) ipl$remoteViewers.add(player.getUUID());
        return viewers;
    }

    @Unique
    private boolean ipl$views(SubLevel subLevel, Player player) {
        Set<UUID> viewers = ipl$viewersBySubLevel.get(subLevel.getUniqueId());
        return viewers != null && viewers.contains(player.getUUID());
    }

    // ------------------------------------------------------------------------
    // Resolve remote viewers. Sable looks trackers up in this level only: in tick a miss
    // means "left the level" (removal), in sendMovementUpdates it silently skips them.
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = {"tick", "sendMovementUpdates"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"
        ),
        require = 2
    )
    private Player ipl$resolveRemoteViewer(ServerLevel lvl, UUID uuid, Operation<Player> original) {
        Player player = original.call(lvl, uuid);
        if (player == null && ipl$remoteViewers.contains(uuid)) {
            return lvl.getServer().getPlayerList().getPlayer(uuid);
        }
        return player;
    }

    // ------------------------------------------------------------------------
    // Load decision per sub-level: a view always counts; Sable's range only within the
    // level (it compares coordinates of two different levels otherwise).
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;shouldLoad(Lnet/minecraft/world/entity/player/Player;Lorg/joml/Vector3dc;)Z"
        ),
        require = 1
    )
    private boolean ipl$shouldLoadByView(
        SubLevelTrackingSystem self, Player player, Vector3dc position,
        Operation<Boolean> original, @Local ServerSubLevel subLevel
    ) {
        boolean views = ipl$views(subLevel, player);
        if (player.level() != level) return views;
        return views || original.call(self, player, position);
    }

    // ------------------------------------------------------------------------
    // Bootstrap remote viewers that are not tracking yet (Sable only adds players of this
    // level). HEAD of sendBoundsUpdates: right after tick's add/remove passes, inside the
    // dimension-tagging wrap below.
    // ------------------------------------------------------------------------

    @Inject(method = "sendBoundsUpdates", at = @At("HEAD"), require = 1)
    private void ipl$bootstrapRemoteViewers(SubLevelContainer container, CallbackInfo ci) {
        if (ipl$remoteViewers.isEmpty()) return;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            Set<UUID> viewers = ipl$viewersBySubLevel.get(subLevel.getUniqueId());
            if (viewers == null) continue;

            ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;
            Collection<UUID> tracking = serverSubLevel.getTrackingPlayers();
            for (UUID uuid : viewers) {
                if (!ipl$remoteViewers.contains(uuid) || tracking.contains(uuid)) continue;
                ServerPlayer viewer = level.getServer().getPlayerList().getPlayer(uuid);
                if (viewer == null) continue;
                tracking.add(uuid);
                IplStraddleSessionSync.sendFrameTo(viewer, serverSubLevel.getUniqueId());
                PacketRedirection.withForceRedirect(level, () -> sendFullSync(viewer, serverSubLevel, null));
                // Straddle parity is server state (see IplStraddleSessionSync); a viewer
                // starting to track mid-crossing needs the current session snapshot too.
                IplStraddleSessionSync.sendSnapshotTo(viewer, serverSubLevel.getUniqueId());
            }
        }
    }

    /**
     * Bounds changes go to every tracker. Sable's {@code playerSink()} resolves trackers in
     * this level only; a remote viewer would keep stale plot bounds, and blocks added
     * meanwhile would stay outside its render grid until a re-sync.
     */
    @WrapOperation(
        method = "sendBoundsUpdates",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;playerSink()Lfoundry/veil/api/network/VeilPacketManager$PacketSink;"
        ),
        require = 1
    )
    private VeilPacketManager.PacketSink ipl$boundsToAllTrackers(
        ServerSubLevel subLevel, Operation<VeilPacketManager.PacketSink> original
    ) {
        return packet -> {
            for (UUID uuid : subLevel.getTrackingPlayers()) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
                if (player != null) player.connection.send(packet);
            }
        };
    }

    // ------------------------------------------------------------------------
    // Dimension tagging: bounds/movement packets emitted from tick carry this level's
    // dimension, so IP's client unwrap applies them to this level's container whatever
    // dimension the client currently displays.
    // ------------------------------------------------------------------------

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendBoundsUpdates(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V"
        )
    )
    private void ipl$wrapBoundsUpdatesInRedirect(SubLevelTrackingSystem self, SubLevelContainer container) {
        PacketRedirection.withForceRedirect(level, () -> sendBoundsUpdates(container));
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendMovementUpdates(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V"
        )
    )
    private void ipl$wrapMovementUpdatesInRedirect(SubLevelTrackingSystem self, SubLevelContainer container) {
        PacketRedirection.withForceRedirect(level, () -> sendMovementUpdates(container));
    }

    // ------------------------------------------------------------------------
    // Full syncs Sable sends from tick (new sub-levels, players entering range) carry the
    // dimension too: untagged, the client applies them to whatever level it displays at
    // that moment, which is the wrong one while a player is switching dimensions.
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendFullSync(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"
        ),
        require = 2
    )
    private void ipl$tagFullSync(
        SubLevelTrackingSystem self, ServerPlayer player, ServerSubLevel subLevel, CustomPacketPayload extraPacket,
        Operation<Void> original
    ) {
        // The full sync is in the body's CURRENT chart: tell the client which one first.
        IplStraddleSessionSync.sendFrameTo(player, subLevel.getUniqueId());
        PacketRedirection.withForceRedirect(level, () -> original.call(self, player, subLevel, extraPacket));
    }

    // ------------------------------------------------------------------------
    // TCP instead of Sable's UDP path, which bypasses Connection.send and with it the
    // dimension tagging above, so the client would apply the update to the wrong container.
    // ------------------------------------------------------------------------

    /**
     * TCP for every player that sees another level (remote viewers, and players near a portal,
     * who can switch dimension at any moment): an untagged UDP snapshot or clock packet that the
     * client handles after the switch would land in the wrong container, freezing one level's
     * sub-levels and throwing the other level's clock onto foreign stamps. The host of a
     * singleplayer world uses TCP as well: there Sable's "UDP" is only a local queue that the
     * client drains later on whatever level it displays then.
     */
    @WrapOperation(
        method = "sendMovementUpdates",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;isConnectedTo(Lnet/minecraft/server/level/ServerPlayer;)Z"
        ),
        require = 1
    )
    private boolean ipl$forceTaggedTCP(SableUDPServer server, ServerPlayer player, Operation<Boolean> original) {
        if (player.serverLevel() != level || player.connection.getRemoteAddress() instanceof LocalAddress
            || ipl$seesOtherLevel(player)) {
            return false;
        }
        return original.call(server, player);
    }

    @Unique
    private boolean ipl$seesOtherLevel(ServerPlayer player) {
        for (ResourceKey<Level> dimension : ImmPtlChunkTracking.getVisibleDimensions(player)) {
            if (dimension != level.dimension()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Stop-tracking packets carry this level's dimension as well: sendRemoval fires from
    // tick's removal pass and from onSubLevelRemoved, both outside the wraps above.
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = {"tick", "onSubLevelRemoved"},
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendRemoval(Lfoundry/veil/api/network/VeilPacketManager$PacketSink;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"
        ),
        require = 1
    )
    private void ipl$stampRemovalWithDimension(
        SubLevelTrackingSystem self,
        VeilPacketManager.PacketSink sink,
        ServerSubLevel subLevel,
        Operation<Void> original
    ) {
        PacketRedirection.withForceRedirect(level, () -> original.call(self, sink, subLevel));
    }
}
