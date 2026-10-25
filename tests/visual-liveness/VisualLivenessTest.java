package ipl.sable.client;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
public class VisualLivenessTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static boolean live(BlockEntity b){return IplHostedBlockEntityLiveness.isLive(b);}
 public static void main(String[]args){
  var be=new BlockEntity();check(!live(be),"null level rejected");
  var world=new Level();be.level=world;check(live(be),"ordinary world lifecycle not overridden");
  world.storage=true;check(!live(be),"missing storage container rejected");
  var c=new SubLevelContainer();world.container=c;check(!live(be),"missing plot rejected");
  var p=new SubLevelContainer.Plot();c.plot=p;check(!live(be),"missing holder rejected without chunk loads");
  var h=new SubLevelContainer.Holder();p.holder=h;check(!live(be),"missing actual chunk rejected");
  var chunk=new SubLevelContainer.Chunk();h.chunk=chunk;check(!live(be),"unregistered BE rejected");
  chunk.bes.put(be.pos,be);check(live(be),"registered live identity accepted");
  var replacement=new BlockEntity();replacement.level=world;chunk.bes.put(be.pos,replacement);
  check(!live(be),"old visual identity retired even when replacement exists at SAME POSITION");check(live(replacement),"new replacement identity preserved");
  chunk.state.has=false;check(!live(replacement),"air/non-BE state retires ghost even without setRemoved");
  chunk.state.has=true;chunk.state.valid=false;check(!live(replacement),"incompatible state retires old visual");
  chunk.state.valid=true;replacement.removed=true;check(!live(replacement),"explicit removed BE rejected");
  replacement.removed=false;p.sub.removed=true;check(!live(replacement),"removed ship retires all visuals");
  p.sub.removed=false;check(live(replacement),"valid identity not falsely retired");
  chunk.bes.clear();check(!live(replacement),"silent chunk removal retires visual without F3+A");
  System.out.println("PASS "+checks+" production visual-identity/liveness assertions (fixture chunks)");
 }
}
