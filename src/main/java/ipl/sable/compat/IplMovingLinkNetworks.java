package ipl.sable.compat;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import dev.ryanhcode.sable.Sable;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import ipl.sable.mixin.IplCreateLinkConnectionsAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import org.joml.Vector3d;
import java.util.*;
/** Refresh wireless range after motion, and migrate network membership on a parent flip. */
public final class IplMovingLinkNetworks {
    private record Position(LevelAccessor world, Vector3d point) {}
    private static final Map<IRedstoneLinkable,Position> LAST = new IdentityHashMap<>();
    private IplMovingLinkNetworks() {}
    public static void clear() { LAST.clear(); }
    public static void tick(MinecraftServer server) {
        var connections = IplCreateLinkConnectionsAccessor.ipl$connections();
        Set<IRedstoneLinkable> live = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Set<IRedstoneLinkable>> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Set<IRedstoneLinkable>,LevelAccessor> worlds = new IdentityHashMap<>();
        record Move(Set<IRedstoneLinkable> from,IRedstoneLinkable actor,ServerLevel target) {}
        var moves = new ArrayList<Move>();
        for (var worldEntry : connections.entrySet()) {
            if (!(worldEntry.getKey() instanceof ServerLevel world) || world.getServer() != server) continue;
            for (var network : worldEntry.getValue().values()) {
                worlds.put(network,world);
                for (IRedstoneLinkable actor : network) {
                    if (!actor.isAlive()) continue;
                    live.add(actor);
                    var sub = Sable.HELPER.getContaining(world,actor.getLocation());
                    if (sub == null || !IplDimAgnostic.isHosted(sub)) continue;
                    ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
                    if (parent == null) continue;
                    var pos = actor.getLocation();
                    Vector3d point = sub.logicalPose().transformPosition(new Vector3d(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5));
                    Position previous = LAST.put(actor,new Position(parent,point));
                    if (previous == null || previous.world != parent || previous.point.distanceSquared(point) > 1.0e-10) dirty.add(network);
                    if (world != parent) moves.add(new Move(network,actor,parent));
                }
            }
        }
        LAST.keySet().retainAll(live);
        for (Move move : moves) {
            var networks = connections.get(move.target);
            if (networks == null) continue; // world not loaded: do not invent a network space
            move.from.remove(move.actor);
            dirty.add(move.from);
            var network = networks.computeIfAbsent(move.actor.getNetworkKey(), k -> new LinkedHashSet<>());
            network.add(move.actor);
            worlds.put(network,move.target);
            dirty.add(network);
        }
        // Refresh from each actor, including receivers that have just moved OUT of range.
        // Merely updating the transmitter leaves those receivers stuck at their old power.
        for (var network : dirty) {
            LevelAccessor world = worlds.get(network);
            if (world == null) continue;
            for (IRedstoneLinkable actor : new ArrayList<>(network)) {
                if (actor.isAlive()) Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(world,actor);
            }
        }
    }
}
