package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Extend Sable's per-level {@code SubLevelTrackingSystem} with cross-dim portal-viewer
 * awareness.
 *
 * <p><b>Problem:</b> Sable's tracking is per-level. {@code SubLevelTrackingSystem.tick()}
 * iterates only {@code this.level.players()} when deciding who tracks each sub-level. When
 * a player crosses a portal to another dimension and looks back through the portal at a
 * sub-level (airship) in the source dim, they receive no further movement updates -- the
 * source-dim's tracking system no longer sees them as a tracker. Visually: the airship
 * freezes/jitters because the client's interpolator extrapolates from the last received
 * snapshot but never gets corrected.
 *
 * <p><b>Fix:</b> integrate IP's portal-aware chunk tracking ({@link ImmPtlChunkTracking})
 * with Sable's per-level tracking system. Every server tick:
 * <ol>
 *   <li>Compute, for each sub-level, the set of cross-dim viewers (players in a different
 *       dim whose IP chunk-tracking includes the sub-level's chunk via a portal view).</li>
 *   <li>Suppress Sable's "this player isn't in {@code level.players()}, remove them" path
 *       for cross-dim viewers (they're not in source-dim's player list, but they should
 *       still receive sub-level updates).</li>
 *   <li>Add newly-arrived cross-dim viewers to {@code serverSubLevel.getTrackingPlayers()}
 *       and emit a full sync ({@link SubLevelTrackingSystem#sendFullSync}) to bootstrap
 *       their client-side {@code ClientSubLevel}.</li>
 *   <li>Wrap {@code sendBoundsUpdates} and {@code sendMovementUpdates} in
 *       {@link PacketRedirection#withForceRedirect}, so packets emitted to cross-dim
 *       recipients are tagged with the source dim. IP's client-side
 *       {@code MixinClientboundCustomPayloadPacket} unwraps and dispatches in the
 *       source-dim context, so the client's source-dim {@code ClientSubLevelContainer}
 *       receives the update.</li>
 *   <li>Force TCP for cross-dim recipients in movement updates. Sable's UDP fast-path
 *       bypasses {@code Connection.send} and therefore IP's packet-wrapping mixin, so
 *       a cross-dim recipient receiving via UDP would have the packet in their current
 *       dim's context (wrong). Forcing TCP routes those updates through the wrapping
 *       infrastructure. Same-dim recipients keep using UDP.</li>
 * </ol>
 *
 * <p>Cleanup is automatic: when a cross-dim viewer stops viewing the sub-level (walks
 * away from the portal, e.g.), they drop out of {@link ImmPtlChunkTracking}'s view list.
 * Our HEAD population doesn't include them. Our removal-suppression doesn't fire. Sable's
 * normal removal path then sees {@code level.getPlayerByUUID(uuid) == null} and removes
 * them, emitting StopTracking through the wrapped path so the client clears its state.
 */
@Pseudo
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class SableCrossDimTrackingMixin {

    @Shadow @Final private ServerLevel level;

    @Shadow
    private void sendFullSync(ServerPlayer player, ServerSubLevel subLevel, CustomPacketPayload extraPacket) {
        throw new AssertionError(); // shadow stub
    }

    @Shadow
    private void sendBoundsUpdates(SubLevelContainer container) {
        throw new AssertionError();
    }

    @Shadow
    private void sendMovementUpdates(SubLevelContainer container) {
        throw new AssertionError();
    }

    /**
     * Per-tick computed: for each sub-level (by UUID), the set of cross-dim viewer UUIDs.
     * Populated at HEAD of {@code tick()} and consumed throughout the rest of the tick.
     * Cleared at HEAD of every tick so stale state from previous ticks doesn't leak.
     */
    @Unique private final Map<UUID, Set<UUID>> ipl$crossDimViewersBySubLevel = new HashMap<>();

    /**
     * Union of all sub-level cross-dim viewer UUIDs for the current tick. Used by
     * {@code shouldLoad}'s @ModifyReturnValue which has no sub-level context, only a
     * player; force-true return for any UUID in this set keeps cross-dim viewers in
     * tracking across all sub-levels they view (or any sub-level, conservatively).
     */
    @Unique private final Set<UUID> ipl$crossDimViewerUnion = new HashSet<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void ipl$collectCrossDimViewers(SubLevelContainer container, CallbackInfo ci) {
        ipl$crossDimViewersBySubLevel.clear();
        ipl$crossDimViewerUnion.clear();

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;

            Vector3dc pos = subLevel.logicalPose().position();
            ChunkPos chunkPos = new ChunkPos(BlockPos.containing(pos.x(), pos.y(), pos.z()));

            List<ServerPlayer> viewers = ImmPtlChunkTracking.getPlayersViewingChunk(
                level.dimension(), chunkPos.x, chunkPos.z, false
            );
            if (viewers.isEmpty()) continue;

            Set<UUID> crossDim = new HashSet<>();
            for (ServerPlayer viewer : viewers) {
                if (viewer.serverLevel() != level) {
                    UUID uuid = viewer.getGameProfile().getId();
                    crossDim.add(uuid);
                    ipl$crossDimViewerUnion.add(uuid);
                }
            }
            if (!crossDim.isEmpty()) {
                ipl$crossDimViewersBySubLevel.put(subLevel.getUniqueId(), crossDim);
            }
        }
    }

    /**
     * Sable's removal loop calls {@code level.getPlayerByUUID(uuid)} and removes the
     * UUID from tracking if the result is null. For cross-dim viewers, the source-level
     * doesn't have the player (they're in a different level), so vanilla returns null.
     * We override to return the cross-dim player from the server-wide list, which makes
     * Sable's null-check pass and skip the removal.
     */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/Player;"),
        require = 0
    )
    private Player ipl$injectCrossDimPlayer(ServerLevel lvl, UUID uuid, Operation<Player> original) {
        Player p = original.call(lvl, uuid);
        if (p != null) return p;
        if (ipl$crossDimViewerUnion.contains(uuid)) {
            // Player is cross-dim viewing some sub-level in this level; return them so
            // Sable's null-check passes and the removal path is skipped.
            return lvl.getServer().getPlayerList().getPlayer(uuid);
        }
        return null;
    }

    /**
     * After Sable's null-check passes (thanks to {@code ipl$injectCrossDimPlayer}), the
     * next check is {@code !shouldLoad(player, entityPos)}. For cross-dim viewers,
     * Sable's coord-distance-based {@code shouldLoad} can return false (e.g. an airship
     * far from the player's literal nether coordinates). Force true so the !shouldLoad
     * branch doesn't trigger removal. Same-dim players are unaffected.
     */
    @ModifyReturnValue(
        method = "shouldLoad",
        at = @At("RETURN"),
        remap = false
    )
    private boolean ipl$forceShouldLoadForCrossDim(boolean original, Player player, Vector3dc entityPosition) {
        if (original) return true;
        if (player instanceof ServerPlayer sp && sp.serverLevel() != level) {
            UUID uuid = sp.getGameProfile().getId();
            if (ipl$crossDimViewerUnion.contains(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Just before {@code sendBoundsUpdates}: add any newly-arrived cross-dim viewers
     * (those present in our per-tick set but not yet in Sable's tracking set) and emit
     * a full sync to each. Subsequent {@code sendBoundsUpdates} and
     * {@code sendMovementUpdates} naturally include them because they iterate the
     * tracking set.
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendBoundsUpdates(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V"
        )
    )
    private void ipl$addNewCrossDimViewersBeforeUpdates(SubLevelContainer container, CallbackInfo ci) {
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;
            Set<UUID> crossDim = ipl$crossDimViewersBySubLevel.get(serverSubLevel.getUniqueId());
            if (crossDim == null || crossDim.isEmpty()) continue;

            Collection<UUID> tracking = serverSubLevel.getTrackingPlayers();
            for (UUID uuid : crossDim) {
                if (tracking.contains(uuid)) continue;
                ServerPlayer viewer = level.getServer().getPlayerList().getPlayer(uuid);
                if (viewer == null) continue;
                tracking.add(uuid);
                PacketRedirection.withForceRedirect(level, () -> {
                    sendFullSync(viewer, serverSubLevel, null);
                });
            }
        }
    }

    /**
     * Wrap {@code sendBoundsUpdates} in {@code PacketRedirection.withForceRedirect}
     * so bound-change packets get tagged with the source dim. Same-dim recipients receive
     * wrapped packets that IP's client mixin unwraps in the same-dim context, which is a
     * no-op (no behavioral change). Cross-dim recipients get the packet dispatched in
     * the source-dim's {@code ClientSubLevelContainer}.
     */
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

    /**
     * Force TCP for cross-dim recipients in {@code sendMovementUpdates}.
     *
     * <p>Sable's UDP fast-path ({@code SableUDPServer.sendUDPPacket}) bypasses
     * {@code Connection.send} and therefore IP's
     * {@code MixinServerGamePacketListenerImpl_Redirect} packet-wrap. A cross-dim
     * recipient receiving a movement update via UDP would have the packet land in their
     * current-dim context (wrong dim's {@code ClientSubLevelContainer}). By making
     * {@code isConnectedTo} return false for cross-dim recipients, we route their
     * updates through Sable's TCP fallback, which goes through {@code Connection.send}
     * and gets wrapped by IP's outgoing-packet mixin under the active
     * {@code PacketRedirection.withForceRedirect} from our wrap above.
     *
     * <p>Same-dim recipients keep using UDP (the typical fast path).
     */
    @WrapOperation(
        method = "sendMovementUpdates",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;isConnectedTo(Lnet/minecraft/server/level/ServerPlayer;)Z"
        ),
        require = 0
    )
    private boolean ipl$forceCrossDimToTCP(SableUDPServer server, ServerPlayer player, Operation<Boolean> original) {
        if (player.serverLevel() != level) {
            return false;
        }
        return original.call(server, player);
    }
}
