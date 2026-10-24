package ipl.sable.dim;
import net.minecraft.world.level.Level;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Vector3d;
/** Tests the production boundary against small ownership fixtures, not Minecraft runtime. */
public class EnvironmentFrameTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  Level storage=new Level(false,true), parent=new Level(false,false), other=new Level(false,false);
  Vector3d p=new Vector3d(-.5,7,-.5);
  var ordinary=IplEnvironmentFrame.resolve(parent,p);
  check(ordinary.level()==parent&&ordinary.position()==p,"ordinary query untouched");
  check(IplEnvironmentFrame.resolve(storage,p).level()==storage,"no owner means no guess");
  var c=new SubLevelContainer();c.x=-1;c.z=-1;SubLevelContainer.value=c;
  var s=new SubLevel();s.parent=parent;c.sub=s;
  s.pose.translation(30,20,10).rotateY(Math.PI/2);
  var sample=IplEnvironmentFrame.resolve(storage,p);
  check(sample.level()==parent,"exact negative-coordinate plot owner");
  check(new Vector3d(sample.position()).distance(new Vector3d(29.5,27,10.5))<1e-9,"plot position transformed exactly once");
  check(p.equals(new Vector3d(-.5,7,-.5)),"caller vector unchanged");
  IplWorldFrameContext.value=other;
  check(IplEnvironmentFrame.resolve(storage,p).level()==parent,"exact owner beats unrelated outer scope");
  Vector3d world=new Vector3d(100,80,100);
  var scoped=IplEnvironmentFrame.resolve(storage,world);
  check(scoped.level()==other&&scoped.position()==world,"world coordinates preserved in explicit frame");
  check(IplEnvironmentFrame.resolve(parent,world).level()==parent,"scope never changes ordinary dimension");
  s.removed=true;
  check(IplEnvironmentFrame.resolve(storage,p).level()==other,"removed owner ignored");
  IplWorldFrameContext.value=storage;
  check(IplEnvironmentFrame.contextualLevel(storage)==null,"storage cannot become environment");
  IplWorldFrameContext.value=null;s.removed=false;s.parent=storage;
  check(IplEnvironmentFrame.resolve(storage,p).level()==storage,"unresolved parent stays honest");
  s.parent=other;
  check(IplEnvironmentFrame.resolve(storage,p).level()==other,"parent flip read live, not cached");
  Level client=new Level(true,true);IplClientWorldFrameContext.value=parent;
  check(IplEnvironmentFrame.resolve(client,world).level()==parent,"client frame used independently");
  check(IplWorldFrameContext.current()==null,"client query does not leak server frame");
  System.out.println("PASS: "+checks+" environment-frame fixture assertions");
 }
}
