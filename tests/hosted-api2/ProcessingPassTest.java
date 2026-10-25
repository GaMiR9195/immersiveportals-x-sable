package ipl.sable.dim;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
/** Production IplProcessingPass + IplSubLevelReadViews frame discipline (fixture worlds). */
public class ProcessingPassTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static SubLevel ship(ServerLevel storage,ServerLevel parent){var s=new SubLevel();s.level=storage;s.parent=parent;return s;}
 public static void main(String[]args){
  var storage=new ServerLevel(true);var overworld=new ServerLevel(false);var nether=new ServerLevel(false);
  var hosting=new SubLevelContainer();hosting.level=storage;IplDimAgnostic.hosting=hosting;
  var world=new SubLevelContainer();world.level=overworld;
  var s1=ship(storage,overworld);var s2=ship(storage,nether);hosting.subs.add(s1);hosting.subs.add(s2);

  check(IplProcessingPass.current()==null&&!IplProcessingPass.isPassOf(hosting),"no pass outside container work");
  check(!IplWorldFrameContext.rollingActive(),"no rolling frame outside a pass");
  IplProcessingPass.unit(()->check(IplProcessingPass.current()==null,"unit outside a pass is a plain call"));

  // Container tick pass: framed enumeration arms each element's world, never rolls.
  IplProcessingPass.run(hosting,()->{
   check(IplProcessingPass.isPassOf(hosting),"pass belongs to its container");
   check(!IplWorldFrameContext.rollingActive(),"container tick never rolls (tracking sees storage audience)");
   check(IplWorldFrameContext.current()==null,"pass opens without a frame");
   var seen=new ArrayList<ServerLevel>();
   for(SubLevel s:IplSubLevelReadViews.framed(hosting.subs)){
    seen.add(IplWorldFrameContext.current());
    check(IplSubLevelReadViews.isElementFrame(IplWorldFrameContext.current()),"element hand-out marks its frame");
   }
   check(seen.equals(List.of(overworld,nether)),"each ship processed in its own world frame");
   check(IplWorldFrameContext.current()==null,"iteration end restores the pre-loop frame");
   var view=IplSubLevelReadViews.framed(hosting.subs);
   view.get(1);check(IplWorldFrameContext.current()==nether,"indexed access arms the element frame");
   view.forEach(s->check(IplWorldFrameContext.current()==s.parent,"forEach arms per element"));
   check(IplWorldFrameContext.current()==nether,"forEach restores the frame it started with");
   for(SubLevel s:IplSubLevelReadViews.framed(hosting.subs)){break;} // addon loop that breaks early
   check(IplWorldFrameContext.current()==overworld,"early break leaves the element frame armed inside the pass");
  });
  check(IplWorldFrameContext.current()==null&&IplSubLevelReadViews.elementFrame()==null,"pass exit never leaks an element frame");
  check(IplProcessingPass.current()==null,"pass closed");

  // Physics phase of the storage pass: frames roll with the ship each listener resolves.
  IplProcessingPass.runPhysics(hosting,()->{
   check(IplWorldFrameContext.rollingActive(),"storage physics phase rolls");
   IplWorldFrameContext.notifyPlotResolved(s1);
   check(IplWorldFrameContext.current()==overworld,"resolving a hosted plot follows that ship's world");
   IplWorldFrameContext.notifyPlotResolved(s2);
   check(IplWorldFrameContext.current()==nether,"next resolved ship re-arms its own world");
   IplProcessingPass.unit(()->{
    check(IplWorldFrameContext.current()==null&&IplSubLevelReadViews.elementFrame()==null,"listener unit starts without the previous listener's rolling frame");
    IplWorldFrameContext.notifyPlotResolved(s1);
    check(IplWorldFrameContext.current()==overworld,"rolling continues inside a unit");
   });
   check(IplWorldFrameContext.current()==nether,"unit restores the pass state");
   // explicit seam frame (BE tick, interaction, collision callback) is never rolled over
   ServerLevel prev=IplWorldFrameContext.push(overworld);IplSubLevelReadViews.restoreElementFrame(null);
   IplWorldFrameContext.notifyPlotResolved(s2);
   check(IplWorldFrameContext.current()==overworld,"seam frame wins over rolling");
   IplProcessingPass.unit(()->check(IplWorldFrameContext.current()==overworld,"unit never clears a seam frame"));
   IplWorldFrameContext.pop(prev);
   var removed=ship(storage,overworld);removed.removed=true;
   IplSubLevelReadViews.restoreElementFrame(nether);IplWorldFrameContext.pop(nether);
   IplWorldFrameContext.notifyPlotResolved(removed);
   check(IplWorldFrameContext.current()==nether,"removed ships never arm a frame");
   // nested owner pass of a world container inside the storage physics phase
   IplProcessingPass.run(world,()->{
    check(IplProcessingPass.isPassOf(world)&&!IplProcessingPass.isPassOf(hosting),"nested pass is owner-local");
    check(!IplWorldFrameContext.rollingActive()&&IplWorldFrameContext.current()==null,"nested world pass neither rolls nor inherits a frame");
   });
   check(IplProcessingPass.isPassOf(hosting)&&IplWorldFrameContext.rollingActive()&&IplWorldFrameContext.current()==nether,"nested pass restores outer pass, rolling and frame");
   // native step from inside the pass belongs to no container
   IplProcessingPass.runOutside(()->{
    check(IplProcessingPass.current()==null&&IplWorldFrameContext.current()==null&&!IplWorldFrameContext.rollingActive(),"native step runs outside any pass");
    IplWorldFrameContext.notifyPlotResolved(s1);
    check(IplWorldFrameContext.current()==null,"no rolling outside a pass");
   });
   check(IplProcessingPass.isPassOf(hosting)&&IplWorldFrameContext.current()==nether,"runOutside restores the pass");
  });
  check(!IplWorldFrameContext.rollingActive()&&IplWorldFrameContext.current()==null,"physics phase exit clears rolling and frame");

  IplProcessingPass.runPhysics(world,()->check(!IplWorldFrameContext.rollingActive(),"a world container's physics phase never rolls"));

  // a frame armed before the pass (hosted actor that drove the fused step) is not inherited, but restored
  IplWorldFrameContext.push(nether);
  IplProcessingPass.run(hosting,()->check(IplWorldFrameContext.current()==null,"pass does not inherit the caller's frame"));
  check(IplWorldFrameContext.current()==nether,"caller frame restored after the pass");
  IplWorldFrameContext.pop(null);
  try{IplProcessingPass.runPhysics(hosting,()->{IplWorldFrameContext.notifyPlotResolved(s1);throw new IllegalStateException("boom");});}catch(IllegalStateException expected){}
  check(IplProcessingPass.current()==null&&IplWorldFrameContext.current()==null&&!IplWorldFrameContext.rollingActive(),"exceptions never leak pass state");
  System.out.println("PASS: "+checks+" production processing-pass/frame assertions");
 }
}
