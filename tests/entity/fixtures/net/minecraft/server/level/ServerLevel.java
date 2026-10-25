package net.minecraft.server.level;
import net.minecraft.world.entity.Entity;
public class ServerLevel extends net.minecraft.world.level.Level implements ipl.sable.mixin.IplServerEntityGetterInvoker {
 public final String name;public final boolean storage;public boolean reject,failAfterJoin;public int removals;
 public final Getter getter=new Getter();private final net.minecraft.server.MinecraftServer server=new net.minecraft.server.MinecraftServer();
 public ServerLevel(String n,boolean s){name=n;storage=s;}
 public net.minecraft.server.MinecraftServer getServer(){return server;}public Getter ipl$entityGetter(){return getter;}
 public boolean addFreshEntity(Entity e){
  var actual=ipl.sable.dim.IplHostedEntityOwnership.owner(e,this);if(actual!=this)return actual.addFreshEntity(e);
  if(reject)return false;if(getter.entities.putIfAbsent(e.getUUID(),e)!=null)throw new AssertionError("duplicate join");
  e.level=this;e.callback=r->{removals++;getter.entities.remove(e.getUUID());e.callback=ignored->{};};
  // Simulate onAddedToLevel recomputing a point-sized entity box.
  e.moveTo(e.getX(),e.position().y(),e.getZ(),0,0);
  if(failAfterJoin)throw new IllegalArgumentException("join listener failed");return true;
 }
 public Dimension dimension(){return new Dimension(name);}public record Dimension(String location){}
 public static class Getter {public final java.util.Map<java.util.UUID,Entity> entities=new java.util.HashMap<>();public Entity get(java.util.UUID id){return entities.get(id);}public Iterable<Entity> getAll(){return entities.values();}}
}

