package ipl.sable.compat;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.mixin.IplCreateLinkConnectionsAccessor;
public class MovingNetworksTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 record Actor(BlockPos getLocation) implements IRedstoneLinkable { public boolean isAlive(){return true;} public String getNetworkKey(){return "same-frequency";} }
 public static void main(String[]args){
  var server=new MinecraftServer();var a=new ServerLevel(false);var b=new ServerLevel(false);var storage=new ServerLevel(true);a.server=b.server=storage.server=server;
  var maps=IplCreateLinkConnectionsAccessor.connections;
  maps.put(a,new HashMap<>());maps.put(b,new HashMap<>());maps.put(storage,new HashMap<>());
  Actor tx=new Actor(new BlockPos(20000001,64,20000001)),rx=new Actor(new BlockPos(20001001,64,20000001)),ground=new Actor(new BlockPos(0,64,0));
  SubLevel ship=new SubLevel();ship.parent=a;Sable.HELPER.subs.put(tx.getLocation(),ship);Sable.HELPER.subs.put(rx.getLocation(),ship);
  var legacy=new LinkedHashSet<IRedstoneLinkable>(List.of(tx,rx));maps.get(storage).put(tx.getNetworkKey(),legacy);
  var terrain=new LinkedHashSet<IRedstoneLinkable>(List.of(ground));maps.get(a).put(tx.getNetworkKey(),terrain);
  IplMovingLinkNetworks.tick(server);
  check(legacy.isEmpty(),"legacy storage network drained on migration");
  check(terrain.containsAll(List.of(tx,rx,ground)),"hosted and terrain actors share parent network");
  check(Create.REDSTONE_LINK_NETWORK_HANDLER.updated.size()==3,"all actors refreshed, not just the transmitter");
  Create.REDSTONE_LINK_NETWORK_HANDLER.updated.clear();IplMovingLinkNetworks.tick(server);
  check(Create.REDSTONE_LINK_NETWORK_HANDLER.updated.isEmpty(),"stationary networks not recomputed each tick");
  ship.pose.translation(100,0,0);IplMovingLinkNetworks.tick(server);
  check(Create.REDSTONE_LINK_NETWORK_HANDLER.updated.containsKey(rx)&&Create.REDSTONE_LINK_NETWORK_HANDLER.updated.containsKey(ground),"motion refreshes receivers including stationary ground peers");
  Create.REDSTONE_LINK_NETWORK_HANDLER.updated.clear();ship.parent=b;IplMovingLinkNetworks.tick(server);
  check(terrain.size()==1&&terrain.contains(ground),"parent flip removes membership from old world");
  check(maps.get(b).get(tx.getNetworkKey()).containsAll(List.of(tx,rx)),"parent flip installs new world membership");
  check(Create.REDSTONE_LINK_NETWORK_HANDLER.updated.get(ground)==a&&Create.REDSTONE_LINK_NETWORK_HANDLER.updated.get(rx)==b,"old and new audiences refreshed independently");
  var unloaded=new ServerLevel(false);unloaded.server=server;ship.parent=unloaded;IplMovingLinkNetworks.tick(server);
  check(maps.get(b).get(tx.getNetworkKey()).contains(tx),"unprepared destination does not silently lose actors");
  IplMovingLinkNetworks.clear();Create.REDSTONE_LINK_NETWORK_HANDLER.updated.clear();ship.parent=b;IplMovingLinkNetworks.tick(server);
  check(Create.REDSTONE_LINK_NETWORK_HANDLER.updated.containsKey(rx),"server cleanup drops motion cache");
  System.out.println("PASS: "+checks+" production moving-network reconciliation fixture assertions");
 }
}
