package ipl.sable.transit;
import java.util.*;import net.minecraft.core.BlockPos;import dev.ryanhcode.sable.sublevel.ServerSubLevel;
public class IplPortalVolumeCache {
 private static final Map<UUID,Long> revisions=new HashMap<>();
 public static List<BlockPos> blocks(ServerSubLevel s){return s.blocks;}
 public static long revision(ServerSubLevel s){return revisions.getOrDefault(s.getUniqueId(),0L);}
 public static void invalidate(ServerSubLevel s){revisions.merge(s.getUniqueId(),1L,Long::sum);}
}
