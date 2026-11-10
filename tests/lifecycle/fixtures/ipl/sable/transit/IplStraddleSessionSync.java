package ipl.sable.transit;
import java.util.*; import java.util.function.Supplier;import dev.ryanhcode.sable.sublevel.ServerSubLevel; import net.minecraft.server.MinecraftServer; import qouteall.imm_ptl.core.portal.Portal;
/** Only delivery/encoding are fixtures; batching and deferred broadcast come from production. */
public final class IplStraddleSessionSync {
 public static final Set<StraddleKey> active=new HashSet<>();
 public static final Map<StraddleKey,Double> widths=new HashMap<>();
 public static final List<Set<StraddleKey>> publications=new ArrayList<>();
 public static int failStartBody=-1;
 private static int batchDepth;private static final Map<UUID,MinecraftServer> DEFERRED=new LinkedHashMap<>();
 public static void reset(){active.clear();widths.clear();publications.clear();DEFERRED.clear();batchDepth=0;failStartBody=-1;}
 public static void onSessionStart(MinecraftServer s,ServerSubLevel b,StraddleKey k,Portal p){active.add(k);widths.put(k,p.getWidth());if(b.getRuntimeId()==failStartBody)throw new IllegalStateException("fixture serialization/publication failure");broadcast(s,b.getUniqueId());}
 public static void onSessionEnd(MinecraftServer s,StraddleKey k,String reason){active.remove(k);widths.remove(k);broadcast(s,k.subLevelUuid());}
 public static void sendLabels(MinecraftServer s,UUID ship,net.minecraft.nbt.CompoundTag payload){}
 private static void broadcastNow(MinecraftServer s,UUID ship){publications.add(Set.copyOf(active));}
 // PRODUCTION_BATCH
}
