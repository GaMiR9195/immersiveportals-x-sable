package ipl.sable.mixin;

import ipl.sable.SableBridge;
import ipl.sable.transit.IplEntityFrames;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

/**
 * Входящие пакеты игрока: действия по проекции корабля исполняются в уровне корабля,
 * а движение декодируется в том кадре, в котором его закодировал клиент.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    // ---- Действия по проекции корабля ----
    // Block use/break packets aimed at a ship PROJECTED into the player's level are processed
    // in the level that owns the ship.
    //
    // <p>The portal-aware pick hands back the ship's plot coordinates, and a projected ship's
    // plot lives in another level. Vanilla processing in the player's level could read those
    // blocks (plot bridge) but every write was rejected by the foreign-plot guard, so doors,
    // levers, placing and breaking on a cross-dimension image silently did nothing. The client
    // normally redirects such packets itself ({@code BlockManipulationClient}); this hook makes
    // the server correct for any client that does not (vanilla path, stale target).
    //
    // <p>Перенаправление идёт через тот же механизм IP, что и
    // взаимодействие "через портал" (контекст мира, перенаправленные пакеты обновления
    // блоков, проверка досягаемости по образу корабля).

    @Shadow
    public ServerPlayer player;

    @Inject(
        method = "handleUseItemOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void ipl$useItemOnProjectedShip(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (BlockManipulationServer.REDIRECT_CONTEXT.get() != null) return;
        ServerLevel owner = SableBridge.foreignPlotOwnerLevel(
            this.player.serverLevel(), packet.getHitResult().getBlockPos());
        if (owner == null) return;
        BlockManipulationServer.processUseItemOnIn(owner, this.player, packet);
        ci.cancel();
    }

    @Inject(
        method = "handlePlayerAction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void ipl$playerActionOnProjectedShip(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!BlockManipulationServer.isAttackingAction(packet.getAction())) return;
        if (BlockManipulationServer.REDIRECT_CONTEXT.get() != null) return;
        ServerLevel owner = SableBridge.foreignPlotOwnerLevel(this.player.serverLevel(), packet.getPos());
        if (owner == null) return;
        BlockManipulationServer.processPlayerActionIn(owner, this.player, packet);
        ci.cancel();
    }

    // ---- Движение игрока в кадре корабля ----
    // Decodes a ship-local move packet in the frame the client encoded it in (see
    // {@link IplEntityFrames}). Anchored on the first read of the packet position, which is
    // after Sable's own decode (injected right after {@code ensureRunningOnSameThread}).

    @Inject(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getX(D)D",
            ordinal = 0
        ),
        require = 1
    )
    private void ipl$decodeInAttachedFrame(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        IplEntityFrames.decodeIncomingPosition(this.player, (IplEntityFrames.MovePacketPosition) packet);
    }
}
