package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplStorageAudience;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.List;
import java.util.Set;

/**
 * Dimension-wide deliveries addressed to the storage dimension
 * ({@code PlayerList.broadcastAll(packet, ipl_sable:sublevels)} — NeoForge's
 * {@code PacketDistributor.sendToPlayersInDimension(subLevel.getLevel(), ...)}, vanilla
 * per-level time/weather sync) reach the players of the worlds that storage content is seen
 * from (see {@link IplStorageAudience}). The packet stays stamped with the storage dimension
 * through Immersive Portals' redirection, so the client applies it to the storage world
 * exactly as a player standing there would — only the audience is resolved, never the
 * packet's meaning.
 *
 * <p>Without this, every addon that syncs per-ship state "to the ship's dimension" (scale,
 * custom ship data, ...) reaches nobody once the ship lives in storage.
 */
@Mixin(value = PlayerList.class, priority = 1200)
public abstract class IplStorageDimensionBroadcastMixin {

    @Shadow @Final private MinecraftServer server;

    @Shadow @Final private List<ServerPlayer> players;

    @WrapMethod(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V")
    @SuppressWarnings("unchecked")
    private void ipl$storageAudience(Packet<?> packet, ResourceKey<Level> dimension, Operation<Void> original) {
        original.call(packet, dimension);
        ServerLevel storage = this.server.getLevel(dimension);
        if (!IplDimAgnostic.isHostingLevel(storage)) return;
        Set<ResourceKey<Level>> worlds = IplStorageAudience.viewingWorlds(storage);
        if (worlds.isEmpty()) return;
        for (ServerPlayer player : List.copyOf(this.players)) {
            ResourceKey<Level> at = player.level().dimension();
            if (at == dimension || !worlds.contains(at)) continue;
            PacketRedirection.sendRedirectedMessage(
                player, dimension, (Packet<ClientGamePacketListener>) packet);
        }
    }
}
