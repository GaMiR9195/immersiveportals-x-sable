package ipl.sable.render;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.*;
public class BlockEntityPresenceTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static boolean has(ClientSubLevel s){return IplSubLevelBlockEntityPresence.hasBlockEntities(s);}
 public static void main(String[]args){
  var s=new ClientSubLevel();var data=new VanillaChunkedSubLevelRenderData();s.data=data;
  check(!has(s),"empty plot skips pass");
  var section=new VanillaChunkedSubLevelRenderData.Section();data.sections.add(section);section.compiled.bes.add(new Object());
  check(has(s),"compiled ordinary BER keeps pass");section.compiled.bes.clear();
  var holder=new ClientSubLevel.Holder();s.plot.holders.add(holder);check(!has(s),"unavailable holder not loaded");
  holder.chunk=new ClientSubLevel.Chunk();var global=new ClientSubLevel.BE();holder.chunk.bes.put(1,global);
  check(has(s),"ONLY off-screen/global BER keeps entire render scope alive");
  global.removed=true;check(!has(s),"removed BE cannot retain empty pass");
  holder.chunk.bes.clear();check(!has(s),"actual chunk removal immediately drops presence");
  var single=new VanillaSingleSubLevelRenderData();s.data=single;check(!has(s),"single air no BER");
  single.be=new Object();check(has(s),"single with BER");s.data=new Object();check(has(s),"custom backend not suppressed");
  System.out.println("PASS: "+checks+" production BER-presence assertions");
 }
}
