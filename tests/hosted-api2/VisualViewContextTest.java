package ipl.sable.client;
import net.minecraft.client.multiplayer.ClientLevel;import net.minecraft.core.BlockPos;import ipl.sable.dim.IplClientWorldFrameContext;
public class VisualViewContextTest {
 static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 public static void main(String[]args){
  var storage=new ClientLevel(true);var a=new ClientLevel(false);var b=new ClientLevel(false);var pos=new BlockPos(20000001,64,20000001);IplClientWorldFrameContext.owners.put(pos,a);
  check(IplClientVisualViewContext.resolve(storage)==storage,"unscoped manager identity remains honest");
  var outer=IplClientVisualViewContext.enter(storage,pos);
  check(IplClientVisualViewContext.resolve(storage)==a,"BE-scoped API selection uses parent view");
  check(IplClientVisualViewContext.resolve(b)==b,"unrelated manager in callback unaffected");
  var nested=IplClientVisualViewContext.enter(b,pos);
  check(IplClientVisualViewContext.resolve(storage)==storage,"nested ordinary-world event masks outer frame");
  IplClientVisualViewContext.exit(nested);check(IplClientVisualViewContext.resolve(storage)==a,"outer frame restored");
  nested=IplClientVisualViewContext.enter(storage,new BlockPos(5,64,5));check(IplClientVisualViewContext.resolve(storage)==storage,"unresolved position never guesses parent");
  IplClientVisualViewContext.exit(nested);IplClientVisualViewContext.exit(outer);
  check(IplClientVisualViewContext.resolve(storage)==storage,"no scope leak after event");
  IplClientWorldFrameContext.owners.put(pos,b);outer=IplClientVisualViewContext.enter(storage,pos);
  check(IplClientVisualViewContext.resolve(storage)==b,"parent flip read live");IplClientVisualViewContext.exit(outer);
  IplClientWorldFrameContext.frame=a;
  check(IplClientVisualViewContext.resolve(storage)==a,"hosted render/tick frame answers for the world being drawn");
  check(IplClientVisualViewContext.resolve(b)==b,"world frame never redirects an ordinary world's manager");
  outer=IplClientVisualViewContext.enter(storage,pos);
  check(IplClientVisualViewContext.resolve(storage)==b,"object lifecycle frame wins over the ambient world frame");
  IplClientVisualViewContext.exit(outer);
  IplClientWorldFrameContext.frame=storage;
  check(IplClientVisualViewContext.resolve(storage)==storage,"storage frame never resolves to itself as a world");
  IplClientWorldFrameContext.frame=null;
  check(IplClientVisualViewContext.resolve(storage)==storage,"no ambient frame keeps manager identity honest");
  System.out.println("PASS: "+checks+" production visualization-view fixture assertions");
 }
}
