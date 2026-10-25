package ipl.sable.dim;
import java.util.*;
import org.joml.Vector3d;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import ipl.sable.transit.IplStraddlePoseMap;
public class HostedBoundariesTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 record Box(double min,double max) implements BoundingBox3dc {public boolean intersects(BoundingBox3dc b){Box other=(Box)b;return max>=other.min&&min<=other.max;}}
 static SubLevel sub(ServerLevel parent,double a,double b){var s=new SubLevel();s.parent=parent;s.bounds=new Box(a,b);return s;}
 static List<SubLevel> list(Iterable<SubLevel> it){var l=new ArrayList<SubLevel>();it.forEach(l::add);return l;}
 public static void main(String[]args)throws Exception {
  var storage=new ServerLevel(true);var parent=new ServerLevel(false);var other=new ServerLevel(false);
  var hosting=new SubLevelContainer();hosting.level=storage;IplDimAgnostic.hosting=hosting;
  SubLevelPhysicsSystem.step=new SubLevelPhysicsSystem(other); // LAST Atlas chart, intentionally wrong
  BlockPos plot=new BlockPos(20000001,64,20000001),terrain=new BlockPos(10,64,10);
  IplWorldFrameContext.owners.put(plot,parent);
  check(IplCollisionCallbackContext.wrap(null)==null,"null material callback unchanged");
  final int[] calls={0};
  BlockSubLevelCollisionCallback callback=(pos,second,hit,velocity)-> {
   calls[0]++;
   check(SubLevelPhysicsSystem.getCurrentlySteppingSystem().getLevel()==(pos.equals(plot)?storage:parent),"contact block ownership overrides last chart");
   check(IplWorldFrameContext.current()==parent,"exact parent frame for terrain probes");
   return new BlockSubLevelCollisionCallback.CollisionResult(new Vector3d(1,2,3),true);
  };
  var wrapped=IplCollisionCallbackContext.wrap(callback);
  var result=wrapped.onCollision(plot.x(),64,plot.z(),10,64,10,1,2,3,8,true);
  check(Arrays.equals(result,new double[]{1,2,3,1}),"unmodified JNI callback result");
  wrapped.onCollision(10,64,10,plot.x(),64,plot.z(),1,2,3,8,true);
  check(calls[0]==2,"each contact called exactly once");
  check(IplCollisionCallbackContext.current()==null&&IplWorldFrameContext.current()==null,"no frame leaks");
  check(SubLevelPhysicsSystem.getCurrentlySteppingSystem().getLevel()==other,"Atlas stepping chart restored");
  var prev=IplWorldFrameContext.push(other);
  try {IplCollisionCallbackContext.wrap((pos,b,h,v)->{throw new IllegalStateException("expected");}).sable$onCollision(plot,null,new Vector3d(),8);throw new AssertionError("exception swallowed");}
  catch(IllegalStateException expected){check(IplWorldFrameContext.current()==other&&IplCollisionCallbackContext.current()==null,"exception restores outer scopes");}
  finally{IplWorldFrameContext.pop(prev);}
  var customized=new BlockSubLevelCollisionCallback(){
   public CollisionResult sable$onCollision(BlockPos p,BlockPos q,Vector3d h,double v){throw new AssertionError("overridden JNI adapter bypassed");}
   public double[] onCollision(int x,int y,int z,int a,int b,int c,double hx,double hy,double hz,double v,boolean o){check(IplCollisionCallbackContext.current()==storage,"overridden adapter still scoped");return new double[]{9};}
  };
  check(IplCollisionCallbackContext.wrap(customized).onCollision(plot.x(),64,plot.z(),0,0,0,0,0,0,0,false)[0]==9,"custom JNI adapter preserved");
  var nested=IplCollisionCallbackContext.wrap((p,q,h,v)->{check(IplCollisionCallbackContext.current()==parent,"nested terrain scope");return BlockSubLevelCollisionCallback.CollisionResult.NONE;});
  IplCollisionCallbackContext.wrap((p,q,h,v)->{nested.sable$onCollision(terrain,plot,h,v);check(IplCollisionCallbackContext.current()==storage,"nested contact restores plot scope");return BlockSubLevelCollisionCallback.CollisionResult.NONE;}).sable$onCollision(plot,terrain,new Vector3d(),8);
  final boolean[] unmapped={false};
  IplCollisionCallbackContext.wrap((p,q,h,v)->{unmapped[0]=true;check(IplCollisionCallbackContext.current()==null,"unresolved contact never guesses owner");return BlockSubLevelCollisionCallback.CollisionResult.NONE;}).sable$onCollision(terrain,null,new Vector3d(),8);
  check(unmapped[0],"unresolved callback retained");
  var a=sub(parent,0,1);var b=sub(other,0,1);var c=sub(parent,20,21);var dead=sub(parent,0,1);dead.removed=true;
  hosting.subs.addAll(List.of(a,b,c,dead));Box query=new Box(-1,2);
  check(list(IplHostedSpatialQueries.include(parent,query,List.of())).equals(List.of(a)),"parent query excludes foreign, removed, distant ships");
  check(list(IplHostedSpatialQueries.include(parent,query,List.of(a))).equals(List.of(a)),"helper/container/pipeline layering deduplicates identity");
  var raw=List.of(a,b);check(IplHostedSpatialQueries.include(storage,query,raw)==raw,"unscoped storage query keeps ownership semantics");
  prev=IplWorldFrameContext.push(parent);
  check(list(IplHostedSpatialQueries.include(storage,query,raw)).equals(List.of(a)),"storage query in known frame excludes another world's ships");
  IplWorldFrameContext.pop(prev);
  IplStraddlePoseMap.images.put(b,List.of(new IplStraddlePoseMap.Image(parent,new Box(0,1))));
  check(list(IplHostedSpatialQueries.include(parent,query,List.of())).equals(List.of(a,b)),"explicit portal image remains queryable");
  a.parent=other;
  check(list(IplHostedSpatialQueries.include(parent,query,List.of())).equals(List.of(b)),"parent flip has no stale owner cache");
  a.parent=parent;
  check(!IplReadViewScope.active() && IplWorldFrameContext.current()==null,"no operation scope required");
  var owned=new ArrayList<SubLevel>();
  var hosted=List.of(a,b,c,dead,a);
  var worldView=IplSubLevelReadViews.inWorld(parent,owned,hosted);
  check(worldView.equals(List.of(a,c)),"unscoped public read view includes all live parent ships, not just intersecting ones");
  check(owned.isEmpty(),"public view never mutates physical ownership");
  try {worldView.clear();throw new AssertionError("mutable union");}catch(UnsupportedOperationException expected){checks++;}
  check(IplSubLevelReadViews.inWorld(storage,owned,hosted)==owned,"storage enumeration stays owner-local");
  check(IplSubLevelReadViews.inWorld(other,owned,hosted).equals(List.of(b)),"world read view filters foreign owners");
  a.parent=other;
  check(IplSubLevelReadViews.inWorld(parent,owned,hosted).equals(List.of(c)),"next query follows a parent flip");
  check(IplSubLevelReadViews.inWorld(parent,new ArrayList<>(List.of(c)),hosted).equals(List.of(c)),"identity dedupe covers preexisting membership");
  check(!IplReadViewScope.active(),"optional clip scope inactive");
  int first=IplReadViewScope.enter();check(IplReadViewScope.active(),"clip read scope active");
  int second=IplReadViewScope.enter();IplReadViewScope.exit(second);check(IplReadViewScope.active(),"nested local clip preserves outer read view");
  Thread thread=new Thread(()->{if(IplReadViewScope.active())throw new AssertionError("cross-thread scope leak");});thread.start();thread.join();
  IplReadViewScope.exit(first);check(!IplReadViewScope.active(),"read scope restored");
  System.out.println("PASS: "+checks+" production callback/spatial/read-scope fixture assertions");
 }
}
