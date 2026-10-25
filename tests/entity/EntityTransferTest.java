package ipl.sable.dim;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;
public class EntityTransferTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static Entity create(ServerLevel source){
  IplDimAgnostic.hosting=null;var e=new Entity(source);source.addFreshEntity(e);
  e.moveTo(10,20,30,0,0);e.setBoundingBox(new AABB(5,18,27,18,24,39));return e;
 }
 static void resolve(ServerLevel source,ServerLevel storage){IplDimAgnostic.hosting=new IplDimAgnostic.Container(storage);IplWorldFrameContext.parent=source;}
 public static void main(String[]args){
  var source=new ServerLevel("parent",false);var storage=new ServerLevel("storage",true);var e=create(source);var old=e.getBoundingBox();resolve(source,storage);
  check(IplHostedEntityOwnership.transfer(e,storage,new Vec3(110,20,230)),"transfer accepted");
  check(e.level()==storage&&storage.getter.get(e.getUUID())==e,"same entity identity in target manager");
  check(source.getter.get(e.getUUID())==null&&source.removals==1,"source callback detached exactly once");
  check(e.getBoundingBox().equals(old.move(new Vec3(100,0,200))),"arbitrary retained volume preserved across setPos AND join");
  check(!e.isRemoved(),"entity.remove not invoked");
  int removals=storage.removals;check(IplHostedEntityOwnership.transfer(e,storage,new Vec3(120,20,230)),"same-world reposition");
  check(storage.removals==removals,"same-world move does not detach");
  check(e.getBoundingBox().equals(old.move(new Vec3(110,0,200))),"same-world bounds translated");
  var rejected=create(source);var rejectBox=rejected.getBoundingBox();resolve(source,storage);storage.reject=true;
  check(!IplHostedEntityOwnership.transfer(rejected,storage,new Vec3(210,20,230)),"rejected join reports failure");
  check(rejected.level()==source&&source.getter.get(rejected.getUUID())==rejected,"rejection restored original owner WITHOUT owner-reroute recursion");
  check(rejected.position().equals(new Vec3(10,20,30))&&rejected.getBoundingBox().equals(rejectBox),"rejection restored position and volume");
  storage.reject=false;var thrown=create(source);var thrownBox=thrown.getBoundingBox();resolve(source,storage);storage.failAfterJoin=true;
  try {IplHostedEntityOwnership.transfer(thrown,storage,new Vec3(310,20,330));throw new AssertionError("listener exception swallowed");}
  catch(IllegalArgumentException expected){checks++;}
  check(storage.getter.get(thrown.getUUID())==null,"partial target insertion detached on listener exception");
  check(source.getter.get(thrown.getUUID())==thrown&&thrown.level()==source,"listener exception restores source identity");
  check(thrown.getBoundingBox().equals(thrownBox)&&thrown.position().equals(new Vec3(10,20,30)),"listener exception restores bounds and position");
  check(!IplHostedEntityOwnership.transfer(new Player(source),storage,new Vec3(0,0,0)),"players excluded from plot storage migration");
  check(IplHostedEntityOwnership.owner(thrown,source)==storage,"explicit-transfer bypass cleared after failure");
  IplHostedEntityOwnership.clear();System.out.println("PASS "+checks+" production entity transfer/rollback assertions (fixture managers)");
 }
}
