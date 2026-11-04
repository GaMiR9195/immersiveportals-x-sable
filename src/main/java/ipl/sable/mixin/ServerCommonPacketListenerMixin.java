package ipl.sable.mixin;

import ipl.sable.SableBridge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;

/**
 * Stamps IP's dimension field onto the {@code ClientboundPlayerPositionPacket} that Sable
 * sends when a player mounts a seat on a sub-level.
 *
 * <p>IP appends a dimension {@code ResourceKey} to every {@code ClientboundPlayerPositionPacket}
 * on write ({@code MixinPlayerPositionLookS2CPacket.onWrite}) and fills it in its own
 * {@code teleport()} overwrite. Sable's seat-mount path
 * ({@code ServerPlayerMixin.sable$adjustTeleportPacket}) builds and sends the packet directly,
 * so the field stays {@code null} and encoding throws ({@code EncoderException: Failed to
 * encode packet 'clientbound/minecraft:player_position'}): the player would be disconnected
 * the instant they sit on a Sable seat.
 *
 * <p>The packet's coordinates are sub-level-LOCAL
 * ({@code logicalPose().transformPositionInverse(player.position())}), so the dimension is the
 * one the seat's sub-level lives in ({@link SableBridge#subLevelDimensionOfVehicle}), not
 * necessarily the player's.
 *
 * <p>Sable's handler exists only merged into {@code ServerPlayer} (a mixin cannot target
 * another mixin), so the hook sits on {@code ServerCommonPacketListenerImpl.send(Packet)},
 * which every outbound packet passes through. It acts ONLY on the seat-mount fingerprint — a
 * position packet with a {@code null} IP dimension whose player rides a sub-level vehicle;
 * any other unstamped send still fails loudly instead of being masked. Runs on the server
 * thread ({@code startRiding} → {@code connection.send}).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void ipl$stampSeatMountDimension(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerPositionPacket)) {
            return;
        }
        IEPlayerPositionLookS2CPacket duck = (IEPlayerPositionLookS2CPacket) packet;

        // IP's own teleport() path already stamped a non-null dimension -- leave it.
        // Only a bypass send (Sable's seat mount) reaches here with a null field.
        if (duck.ip_getPlayerDimension() != null) {
            return;
        }

        // send() lives on the common listener; the player only exists on the game subclass
        // (login/config listeners never send position packets).
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl gameListener)) {
            return;
        }
        ServerPlayer player = gameListener.getPlayer();

        // Null when the player does not ride a sub-level vehicle: the field then stays null
        // and the encode failure reveals the other bypass path.
        ResourceKey<Level> subLevelDim =
            SableBridge.subLevelDimensionOfVehicle(player.getVehicle());
        if (subLevelDim != null) {
            duck.ip_setPlayerDimension(subLevelDim);
        }
    }
}
