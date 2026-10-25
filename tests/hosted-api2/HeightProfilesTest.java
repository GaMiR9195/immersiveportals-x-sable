package ipl.sable.dim;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
public class HeightProfilesTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[]args){
  var storage=new ServerLevel(true);var nether=new ServerLevel(false);nether.min=0;nether.max=256;
  var end=new ServerLevel(false);end.min=0;end.max=256;
  var c=new SubLevelContainer();c.level=storage;IplDimAgnostic.hosting=c;
  var sub=new SubLevel();sub.level=storage;sub.parent=nether;var plot=new LevelPlot();plot.sub=sub;c.plots.put("20000:20000",plot);
  var below=new BlockPos(320000,-32,320000);var above=new BlockPos(320000,300,320000);var ordinary=new BlockPos(10,300,10);
  check(IplHostedPlotHeight.profile(nether,below)==storage,"owned plot below parent minimum uses storage");
  check(!IplHostedPlotHeight.profile(nether,below).isOutsideBuildHeight(below),"negative plot y allowed");
  check(!IplHostedPlotHeight.profile(nether,above).isOutsideBuildHeight(above),"plot above parent ceiling allowed");
  check(IplHostedPlotHeight.profile(nether,ordinary)==nether,"ordinary world keeps parent profile");
  check(IplHostedPlotHeight.profile(end,above)==end,"foreign world never borrows profile");
  check(nether.getMinBuildHeight()==0&&nether.getMaxBuildHeight()==256,"scalar parent profile unchanged");
  check(storage.getMinBuildHeight()==-64&&storage.getMaxBuildHeight()==320,"scalar storage layout unchanged");
  check(IplHostedPlotHeight.profile(storage,above)==storage,"storage no recursion");
  sub.removed=true;check(IplHostedPlotHeight.profile(nether,above)==nether,"removed plot no longer authorizes height");
  sub.removed=false;sub.parent=end;check(IplHostedPlotHeight.profile(end,above)==storage,"parent handoff immediately reflected");
  IplDimAgnostic.hosting=null;check(IplHostedPlotHeight.profile(end,above)==end,"missing storage never manufactured");
  System.out.println("PASS "+checks+" position-specific height profile assertions");
 }
}
