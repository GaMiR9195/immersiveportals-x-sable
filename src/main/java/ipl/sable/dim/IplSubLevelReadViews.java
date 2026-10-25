package ipl.sable.dim;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import java.util.*;
/** The public world's read view, independent of transient operation scopes. */
public final class IplSubLevelReadViews {
 private IplSubLevelReadViews() {}
 public static List<SubLevel> inWorld(Level world,List<SubLevel> owned,List<? extends SubLevel> hosted) {
  if(world==null||IplDimAgnostic.isHostingLevel(world))return owned;
  List<SubLevel> result=null;
  Set<SubLevel> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(owned);
  for(var sub:hosted) {
   if(sub.isRemoved()||IplDimAgnostic.getParentLevel(sub)!=world||!seen.add(sub))continue;
   if(result==null)result=new ArrayList<>(owned);
   result.add(sub);
  }
  return result==null?owned:Collections.unmodifiableList(result);
 }
}
