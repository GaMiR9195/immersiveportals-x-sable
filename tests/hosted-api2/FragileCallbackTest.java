package ipl.sable.dim;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.physics.callback.FragileBlockCallback;
public class FragileCallbackTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[]args){
  var storage=new ServerLevel(true);var parent=new ServerLevel(false);var unrelated=new ServerLevel(false);
  var c=new SubLevelContainer();c.level=storage;IplDimAgnostic.hosting=c;
  SubLevelPhysicsSystem.step=new SubLevelPhysicsSystem(unrelated);
  var plot=new BlockPos(20000001,64,20000001);var terrain=new BlockPos(10,64,10);
  IplWorldFrameContext.owners.put(plot,parent);
  var wrapped=IplCollisionCallbackContext.wrap(FragileBlockCallback.INSTANCE);
  storage.blocks.put(plot,new BlockState(new LeavesBlock(),false,true));
  wrapped.onCollision(plot.x(),64,plot.z(),10,64,10,0,0,0,8,true);
  check(storage.destroyed.contains(plot),"stock callback destroys natural leaves in actual plot owner");
  check(parent.destroyed.isEmpty()&&unrelated.destroyed.isEmpty(),"no destruction in wrong dimension");
  parent.blocks.put(terrain,new BlockState(new LeavesBlock(),false,true));
  wrapped.onCollision(10,64,10,plot.x(),64,plot.z(),0,0,0,8,true);
  check(parent.destroyed.contains(terrain),"stock callback destroys natural terrain leaves in physical parent");
  storage.destroyed.clear();storage.blocks.put(plot,new BlockState(new LeavesBlock(),true,true));
  var result=wrapped.onCollision(plot.x(),64,plot.z(),10,64,10,0,0,0,8,true);
  check(storage.destroyed.isEmpty()&&result[3]==0,"upstream intentionally protects persistent/player-placed leaves");
  storage.blocks.put(plot,new BlockState(new Block(),false,true));
  wrapped.onCollision(plot.x(),64,plot.z(),10,64,10,0,0,0,3,true);
  check(storage.destroyed.isEmpty(),"stock trigger threshold unchanged");
  wrapped.onCollision(plot.x(),64,plot.z(),10,64,10,0,0,0,8,true);
  check(storage.destroyed.contains(plot),"other fragile material uses same generic path");
  check(IplCollisionCallbackContext.current()==null&&IplWorldFrameContext.current()==null,"callback scopes restored");
  System.out.println("PASS "+checks+" ORIGINAL upstream fragile callback assertions (fixture worlds, not game)");
 }
}
