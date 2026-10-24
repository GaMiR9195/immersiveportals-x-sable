package ipl.sable.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
/** Route the audience, NOT packet coordinates: Sable owns plot-space sound/event projection. */
@Mixin(value = PlayerList.class, priority = 1200)
public abstract class IplHostedBroadcastViewMixin {
    @Shadow @Final private MinecraftServer server;
    @WrapMethod(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V")
    private void ipl$broadcastView(Player except, double x, double y, double z, double radius,
        ResourceKey<Level> dimension, Packet<?> packet, Operation<Void> original) {
        ServerLevel source = server.getLevel(dimension);
        if (IplDimAgnostic.isHostingLevel(source)) {
            ServerLevel parent = IplWorldFrameContext.resolveParentForPlotBe(source, BlockPos.containing(x,y,z));
            if (parent == null) parent = IplWorldFrameContext.current();
            if (parent != null && !IplDimAgnostic.isHostingLevel(parent)) dimension = parent.dimension();
        }
        original.call(except,x,y,z,radius,dimension,packet);
    }
}
