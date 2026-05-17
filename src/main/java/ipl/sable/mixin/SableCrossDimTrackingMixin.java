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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Cross-dim sub-level tracking, second attempt.
 *
 * <p>Goal: when a player views a sub-level (airship) in dimension A through a portal from
 * dimension B, they should keep receiving live bounds/movement updates so the sub-level
 * doesn't appear frozen. The prior attempt (commit a2844ca, reverted in d37c1cd) made the
 * airship invisible because it missed a hook site -- this version corrects that.
 *
 * <p><b>What the diagnostic mixin proved about Sable's per-tick flow:</b>
 * <ol>
 *   <li>On dim transition, source-dim's {@code tick()} fires the
 *       "{@code player == null} from {@link ServerLevel#getPlayerByUUID}, still online
 *       elsewhere" branch (line 197-204 in Sable's source). It emits a
 *       {@code sendRemoval} via {@code VeilPacketManager.player(serverWidePlayer)} and
 *       removes the UUID from {@code tracking}. The {@code StopTracking} packet then races
 *       the client-side dim switch and lands in the wrong client container.</li>
 *   <li>On dim re-entry, the player returns to {@code level.players()}, the
 *       "add players who SHOULD be tracking but aren't" branch fires, and
 *       {@code sendFullSync} re-bootstraps. Our
 *       {@link SableStartTrackingDedupeMixin} cancels the redundant allocation on the
 *       client side so the stale state is preserved.</li>
 *   <li>While the player is in the destination dim, source-dim's {@code tick} continues
 *       running but with the player absent from {@code tracking}, so no bounds/movement
 *       updates are emitted. Airship is visible (via the stale client state) but frozen.</li>
 *   <li>There's also a small race window where movement packets emitted right before the
 *       server-side teleport completes arrive at the client after the client-side dim
 *       switch and land in the wrong container -- seen as
 *       "Received a sub-level movement packet for a non-existent sub-level" errors.</li>
 * </ol>
 *
 * <p><b>What this mixin does:</b>
 * <ol>
 *   <li>{@code tick HEAD}: query {@link ImmPtlChunkTracking} per sub-level for cross-dim
 *       viewers (players in another dim whose IP chunk-tracking covers the sub-level's
 *       chunk via a portal view). Cache the per-sub-level set and the union.</li>
 *   <li>{@code @WrapOperation} on {@code ServerLevel.getPlayerByUUID} in <b>both</b>
 *       {@code tick} <b>and</b> {@code sendMovementUpdates}: for cross-dim viewers, return
 *       the player from the server-wide list. In {@code tick} this suppresses the removal
 *       path. In {@code sendMovementUpdates} this prevents the silent
 *       {@code if (player == null) continue} that would skip movement-update generation --
 *       <b>the missed site that caused invisibility in the previous attempt</b>.</li>
 *   <li>{@code @ModifyReturnValue} on {@code shouldLoad}: force {@code true} for cross-dim
 *       viewers, so the removal path's distance check doesn't trigger.</li>
 *   <li>{@code @Inject(HEAD)} on {@code sendBoundsUpdates}: bootstrap path. For cross-dim
 *       viewers detected in step 1 but not yet in {@code tracking}, add them and emit one
 *       {@code sendFullSync} so their client allocates the sub-level in the source-dim
 *       {@link dev.ryanhcode.sable.api.sublevel.SubLevelContainer}. Inert for the common
 *       case (player tracked before crossing -> retained via {@code @WrapOperation} ->
 *       already in tracking when this runs).</li>
 *   <li>{@code @Redirect} on the {@code sendBoundsUpdates} and {@code sendMovementUpdates}
 *       calls in {@code tick}: wrap each in {@code PacketRedirection.withForceRedirect(
 *       sourceLevel, ...)}. Packets emitted under this thread-local get tagged with
 *       source-dim; IP's client-side unwrap dispatches them under
 *       {@code ClientWorldLoader.withSwitchedWorldFailSoft(sourceDim)}, so they land in
 *       the source-dim {@code ClientSubLevelContainer} regardless of which dim the client
 *       is currently displaying.</li>
 *   <li>{@code @WrapOperation} on {@code SableUDPServer.isConnectedTo} in
 *       {@code sendMovementUpdates}: force {@code false} for cross-dim recipients so
 *       movement updates fall through to TCP. Sable's UDP path bypasses
 *       {@code Connection.send} and therefore IP's outgoing-packet wrap; sending UDP to a
 *       cross-dim recipient would result in the packet landing in the wrong client
 *       container. Same-dim recipients keep using UDP.</li>
 * </ol>
 *
 * <p><b>What's different from the previous attempt:</b>
 * <ul>
 *   <li>Added the second {@code @WrapOperation} on {@code getPlayerByUUID} in
 *       {@code sendMovementUpdates}. Without it, cross-dim viewers stayed in
 *       {@code tracking} (so bounds updates reached them via the server-wide sink) but
 *       movement updates skipped them silently. Combined with stale bounds + no pose
 *       updates, the client renderer bailed out -> invisible.</li>
 *   <li>Moved the bootstrap {@code @Inject} from "{@code INVOKE sendBoundsUpdates} in
 *       {@code tick}" (which collided with the {@code @Redirect} at the same instruction --
 *       undefined ordering) to {@code @Inject(HEAD)} on the {@code sendBoundsUpdates}
 *       method itself. Clean separation.</li>
 * </ul>
 *
 * <p>{@code SableStartTrackingDedupeMixin} remains essential: when a previously-tracked
 * player returns from cross-dim view, source-dim {@code tick}'s natural "add who should be
 * tracking" branch will fire a second {@code sendFullSync} (because they re-entered
 * {@code level.players()}). Without the dedupe, the client would crash on "Plot already
 * exists" since we never lost the client-side allocation.
 */
@Pseudo
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class SableCrossDimTrackingMixin {

    @Shadow @Final private ServerLevel level;

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

    /** Per-tick: for each sub-level UUID, the set of cross-dim viewer UUIDs. */
    @Unique
    private final Map<UUID, Set<UUID>> ipl$crossDimViewersBySubLevel = new HashMap<>();

    /** Per-tick union across all sub-levels. Used by hooks that don't have sub-level context. */
    @Unique
    private final Set<UUID> ipl$crossDimViewerUnion = new HashSet<>();

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-cross-dim");

    @Unique
    private long ipl$logTickCounter = 0;

    // ------------------------------------------------------------------------
    // 1. Compute cross-dim viewer sets at tick HEAD
    // ------------------------------------------------------------------------

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

            Set<UUID> crossDim = null;
            for (ServerPlayer viewer : viewers) {
                if (viewer.serverLevel() != level) {
                    UUID uuid = viewer.getGameProfile().getId();
                    if (crossDim == null) crossDim = new HashSet<>();
                    crossDim.add(uuid);
                    ipl$crossDimViewerUnion.add(uuid);
                }
            }
            if (crossDim != null) {
                ipl$crossDimViewersBySubLevel.put(subLevel.getUniqueId(), crossDim);
            }
        }

        // Log every 20th tick or whenever the cross-dim union is non-empty (rare/interesting).
        long c = ipl$logTickCounter++;
        if (!ipl$crossDimViewerUnion.isEmpty() || (c % 20) == 0) {
            IPL$LOG.info(
                "[IPL-SABLE-CD] tick#{} dim={} crossDimUnion={} subLevelMap={}",
                c,
                level.dimension().location(),
                ipl$crossDimViewerUnion,
                ipl$crossDimViewersBySubLevel
            );
        }
    }

    // ------------------------------------------------------------------------
    // 2. Suppress removal for cross-dim viewers in tick()'s null-player check
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            // NB: bytecode return descriptor is Lnet/minecraft/world/entity/player/Player;
            // (the class lives in the `player` subpackage, not entity directly). Got this
            // wrong on the first pass which caused require=0 to silently no-op the wrap.
            // Tightened to require=1 to make any future descriptor drift fail loudly.
            target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"
        ),
        require = 1
    )
    private Player ipl$resolveCrossDimInTick(ServerLevel lvl, UUID uuid, Operation<Player> original) {
        Player p = original.call(lvl, uuid);
        if (p != null) return p;
        if (ipl$crossDimViewerUnion.contains(uuid)) {
            return lvl.getServer().getPlayerList().getPlayer(uuid);
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // 3. ALSO resolve cross-dim players in sendMovementUpdates -- this was the
    //    missed site that caused the invisibility regression in the prior attempt.
    //    Without this, movement updates iterate `tracking`, hit `getPlayerByUUID
    //    -> null` for cross-dim viewers, and `continue` past them -> no packets.
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = "sendMovementUpdates",
        at = @At(
            value = "INVOKE",
            // Same descriptor fix as above: return type is .../entity/player/Player not .../entity/Player.
            target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"
        ),
        require = 1
    )
    private Player ipl$resolveCrossDimInMovement(ServerLevel lvl, UUID uuid, Operation<Player> original) {
        Player p = original.call(lvl, uuid);
        if (p != null) return p;
        if (ipl$crossDimViewerUnion.contains(uuid)) {
            Player crossDim = lvl.getServer().getPlayerList().getPlayer(uuid);
            IPL$LOG.info("[IPL-SABLE-CD] sendMovementUpdates resolved cross-dim player uuid={} -> {}  lvl={}", uuid, crossDim, lvl.dimension().location());
            return crossDim;
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // 4. Force shouldLoad=true for cross-dim viewers (their literal world-coord
    //    position is far from the source-dim sub-level, so vanilla distance check
    //    would say "out of range" and trigger the removal path).
    // ------------------------------------------------------------------------

    @ModifyReturnValue(
        method = "shouldLoad",
        at = @At("RETURN"),
        remap = false
    )
    private boolean ipl$forceShouldLoadForCrossDim(boolean original, Player player, Vector3dc entityPosition) {
        if (original) return true;
        if (player instanceof ServerPlayer sp && sp.serverLevel() != level) {
            if (ipl$crossDimViewerUnion.contains(sp.getGameProfile().getId())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // 5. Bootstrap: for any cross-dim viewer NOT yet in tracking (e.g. someone
    //    who walked up to a portal in the dest dim without having tracked the
    //    source-dim sub-level before), add them to tracking + emit a wrapped
    //    sendFullSync. Inert when the viewer was already tracking before
    //    crossing (which is the common case -- they're retained via @WrapOperation
    //    above, so they're already in tracking when this runs).
    //
    //    Anchored at HEAD of sendBoundsUpdates rather than at the invoke site in
    //    tick(), to avoid colliding with the @Redirect on the same instruction.
    //    sendBoundsUpdates is the first packet-emitting call in tick after the
    //    addition/removal passes, so HEAD here is the right bootstrap window.
    // ------------------------------------------------------------------------

    @Inject(method = "sendBoundsUpdates", at = @At("HEAD"), require = 0)
    private void ipl$bootstrapCrossDimViewers(SubLevelContainer container, CallbackInfo ci) {
        if (ipl$crossDimViewersBySubLevel.isEmpty()) return;
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
                // We're already inside the @Redirect's withForceRedirect wrap when
                // sendBoundsUpdates is being called from tick. But we may also be called
                // outside that context (e.g. if sendBoundsUpdates is invoked elsewhere),
                // so set it explicitly here -- nested same-value is a no-op.
                PacketRedirection.withForceRedirect(level, () -> sendFullSync(viewer, serverSubLevel, null));
            }
        }
    }

    // ------------------------------------------------------------------------
    // 6. Wrap sendBoundsUpdates + sendMovementUpdates calls in tick() in
    //    PacketRedirection.withForceRedirect. This tags emitted packets with the
    //    source dim's ResourceKey; IP's client-side unwrap dispatches them under
    //    ClientWorldLoader.withSwitchedWorldFailSoft so they land in the
    //    source-dim ClientSubLevelContainer regardless of the client's active dim.
    // ------------------------------------------------------------------------

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendBoundsUpdates(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V"
        )
    )
    private void ipl$wrapBoundsUpdatesInRedirect(SubLevelTrackingSystem self, SubLevelContainer container) {
        if (!ipl$crossDimViewerUnion.isEmpty()) {
            IPL$LOG.info("[IPL-SABLE-CD] @Redirect bounds wrap firing  dim={}  crossDim={}", level.dimension().location(), ipl$crossDimViewerUnion);
        }
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
        if (!ipl$crossDimViewerUnion.isEmpty()) {
            IPL$LOG.info("[IPL-SABLE-CD] @Redirect movement wrap firing  dim={}  crossDim={}", level.dimension().location(), ipl$crossDimViewerUnion);
        }
        PacketRedirection.withForceRedirect(level, () -> sendMovementUpdates(container));
    }

    // ------------------------------------------------------------------------
    // 7. Force TCP for cross-dim recipients in sendMovementUpdates. Sable's UDP
    //    fast-path bypasses Connection.send and therefore IP's outgoing-packet
    //    wrap; the client would receive an unwrapped packet and route it to its
    //    currently-active container (wrong dim). TCP fallback goes through
    //    Connection.send and gets wrapped by IP under the active
    //    withForceRedirect from @Redirect above.
    // ------------------------------------------------------------------------

    @WrapOperation(
        method = "sendMovementUpdates",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;isConnectedTo(Lnet/minecraft/server/level/ServerPlayer;)Z"
        ),
        require = 1
    )
    private boolean ipl$forceCrossDimToTCP(SableUDPServer server, ServerPlayer player, Operation<Boolean> original) {
        if (player.serverLevel() != level) {
            IPL$LOG.info("[IPL-SABLE-CD] forcing TCP for cross-dim player {} (in {} not {})",
                player.getGameProfile().getName(), player.serverLevel().dimension().location(), level.dimension().location());
            return false;
        }
        return original.call(server, player);
    }
}
