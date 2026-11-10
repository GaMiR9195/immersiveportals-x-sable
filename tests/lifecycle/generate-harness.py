#!/usr/bin/env python3
"""Extract real production members, not duplicate implementations, into the small API fixture.
The full mod is compiled separately against Minecraft/NeoForge. This runner needs only JOML
and fastutil; no server launch, JNI loading, downloaded test framework, or copied business logic.
"""
from pathlib import Path
import sys
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=Path('src/main/java/ipl/sable/transit')
def member(text, marker):
 start=text.index(marker); opening=text.index('{',start); depth=1; end=opening+1
 while depth:
  depth += (text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
tracker=(source/'IplPieceTracker.java').read_text()
markers=[
 'public record FlipChoice(', 'static FlipChoice pendingFlip(', 'private record WatchKey(', 'private static final class Watch {',
 'static final class Body {', 'static Body tracked(', 'static Body trackForContext(',
 'static void settled(', 'private static void seedCurrentFrames(', 'private static void seedCurrentFrame(',
 'static void contextReady(', 'static void repaired(', 'private static void free(', 'static int openSession(',
 'static void endSubtree(', 'private static void settleCounts(', 'private static void recount(',
 'private static boolean nativeHasMaterial(', 'private static FlipChoice flipChoice(', 'private static void reconcile(',
 'private static void rebindObservation(', 'private static void clearNativePieces(', 'static void afterFlip(',
 'private static void pushEdges(', 'private static void pushLabels(', 'static void publish(',
 'static FlipChoice update(', 'static void samplePhysics(', 'static void publishPhysics(', 'public static CompoundTag fullLabels('
]
imports='''package ipl.sable.transit;
import java.util.*; import org.jetbrains.annotations.Nullable;
import dev.ryanhcode.sable.companion.math.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel; import dev.ryanhcode.sable.sublevel.plot.*;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.server.MinecraftServer; import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.*; import net.minecraft.world.level.ChunkPos; import net.minecraft.world.level.chunk.*;
import net.minecraft.nbt.CompoundTag;
import qouteall.imm_ptl.core.portal.Portal;
import ipl.sable.atlas.IplScenes; import ipl.sable.natives.IplRapierNatives;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
'''
body='\n'.join(member(tracker,mark) for mark in markers)
body=body.replace('private static void reconcile(', 'static void reconcile(')
body=body.replace('private static void rebindObservation(', 'static void rebindObservation(')
body+='''
static final Map<UUID,Body> BODIES=new HashMap<>();
private static boolean loggedNodeCap,loggedNoNatives;
private static final Log LOG=new Log(); private static final class Log {void warn(String s,Object... args){}}
private static LevelChunkSection sectionAt(LevelPlot p,int x,int y,int z){return p.section(x,y,z);}
// Only the physics readback/nearby-entity source is a fixture. Boundary orchestration is production.
static java.util.function.Consumer<ServerSubLevel> OBSERVE;
static final Map<UUID,List<Portal>> NEAR=new HashMap<>();
static final Map<UUID,Integer> OBSERVED=new HashMap<>();
private static ServerSubLevel queryBox(ServerSubLevel s,Pose3dc before,Pose3dc now){return s;}
private static List<Portal> portalsNear(ServerLevel level,ServerSubLevel query){return NEAR.getOrDefault(query.getUniqueId(),List.of());}
private static void sample(Body b,ServerSubLevel s,int tick){OBSERVED.merge(s.getUniqueId(),1,Integer::sum);if(OBSERVE!=null)OBSERVE.accept(s);}
}'''
(out/'IplPieceTracker.java').write_text(imports+'public final class IplPieceTracker {\n'+body)
pose=(source/'IplStraddlePoseMap.java').read_text()
imports='''package ipl.sable.transit;
import dev.ryanhcode.sable.companion.math.*; import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond; import org.joml.Quaterniondc; import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable; import java.util.Objects;
import qouteall.imm_ptl.core.portal.Portal; import qouteall.q_misc_util.my_util.DQuaternion;
'''
(out/'IplStraddlePoseMap.java').write_text(imports+'public final class IplStraddlePoseMap {\n'+member(pose,'public static final class StraddleMapping {')+'\n'+member(pose,'public static boolean isApproxIdentity(')+'\n}')
atlas=(source/'IplAtlasStraddleSession.java').read_text()
markers=[
 'private static final class Session {', 'static boolean supports(', 'static boolean open(',
 'static void refreshShip(', 'public static void clear(', 'public static void clearShip(',
 'public static void clearAll(', 'public static boolean hasSessionKey(', 'public static List<StraddleKey> sessionKeysFor(',
 'public static Portal sessionPortal(', 'public static ServerLevel sessionDest(', 'public static int sessionNode(',
 'public static IplStraddlePoseMap.StraddleMapping sessionMapping(', 'private static void sessionsChanged(',
 'public record Promotion(', 'public static List<Promotion> promotionsThrough(', 'static List<StraddleKey> promote('
]
imports='''package ipl.sable.transit;
import java.util.*; import org.jetbrains.annotations.Nullable; import org.joml.Quaterniond;
import dev.ryanhcode.sable.sublevel.ServerSubLevel; import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3; import qouteall.imm_ptl.core.portal.Portal;
import ipl.sable.atlas.IplScenes; import ipl.sable.natives.IplRapierNatives;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
'''
fields='''private static final Map<StraddleKey,Session> SESSIONS=new LinkedHashMap<>();
private static Session[] snapshot=new Session[0]; private static Map<ServerLevel,List<UUID>> shipsByDest=Map.of();
private static boolean loggedScaledSkip;
private static final Log LOG=new Log(); private static final class Log {
 void debug(String s,Object... args){} void warn(String s,Object... args){} void info(String s,Object... args){}
}
static void reset(){clearAll();IplRapierNatives.reset();IplPieceTracker.BODIES.clear();IplPieceTracker.NEAR.clear();IplPieceTracker.OBSERVED.clear();IplPieceTracker.OBSERVE=null;IplStraddleSessionSync.reset();}
'''
(out/'IplAtlasStraddleSession.java').write_text(imports+'public final class IplAtlasStraddleSession {\n'+fields+'\n'.join(member(atlas,m) for m in markers)+'\n}')
sync=(source/'IplStraddleSessionSync.java').read_text()
template=Path('tests/lifecycle/fixtures/ipl/sable/transit/IplStraddleSessionSync.java').read_text()
markers=['static void batch(', 'static <T> T batchResult(', 'private static void broadcast(']
(out/'IplStraddleSessionSync.java').write_text(template.replace('// PRODUCTION_BATCH','\n'.join(member(sync,m) for m in markers)))
controller=(source/'IplTransitController.java').read_text()
imports='''package ipl.sable.transit;
import java.util.*;import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.api.sublevel.*;import ipl.sable.natives.IplRapierNatives;import net.minecraft.server.level.ServerLevel;
'''
markers=['public static void onPhysicsSample(', 'private static List<ServerSubLevel> liveBodies(',
 'private static Map<UUID, IplTransitConnections.Group> coordinateGroups(']
body='\n'.join(member(controller,m) for m in markers)
body=body.replace('private static Map<UUID, IplTransitConnections.Group> coordinateGroups(', 'static Map<UUID, IplTransitConnections.Group> coordinateGroups(')
(out/'IplTransitController.java').write_text(imports+'public final class IplTransitController {\n'+body+'\n}')
