#!/usr/bin/env python3
"""Isolated checks against production route code, not a Minecraft/OpenGL test.
Requires Python 3 + PyYAML, JDK 21+, and a JOML 1.10.5 jar (argument 1).
All generated test doubles/classes live in a TemporaryDirectory.
"""
import json, os, re, subprocess, sys, tempfile
from pathlib import Path
import yaml
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java'
conf = yaml.safe_load((ROOT/'src/main/resources/assets/immersive_portals/shaders/shader_transformation.yaml').read_text())
seen = set()
for cfg in conf['configs']:
    for name in cfg['affectedShaders']:
        assert (cfg['type'], name) not in seen, name
        seen.add((cfg['type'], name))
    if 'block_entity_diffuse' in cfg['affectedShaders']:
        out = '\n'.join(t['replacement'] for t in cfg['transformations'])
        assert 'gbufferModelViewInverse' not in re.sub(r'//[^\n]*', '', out)
        assert out.count('dot(_ipl_eyePos,') == 3
for name in ['rendertype_entity_solid','rendertype_entity_translucent_cull','rendertype_text','rendertype_text_see_through']:
    assert ('vs', name) in seen, name
mixins = json.loads((ROOT/'src/main/resources/ipl_sable.mixins.json').read_text())
for name in mixins['client'] + mixins['mixins']:
    assert (JAVA/'ipl/sable/mixin'/Path(name.replace('.','/')).with_suffix('.java')).is_file(), name
print('PASS: shader coverage, unique configurations, Iris no-pack coordinate contract, mixin paths')
if len(sys.argv) != 2:
    sys.exit('Pass the path to joml-1.10.5.jar to run Java geometry checks.')
STUBS = {
'org.jetbrains.annotations.Nullable': '@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER}) public @interface Nullable {}',
'net.minecraft.resources.ResourceLocation': 'public record ResourceLocation(String value) { public static ResourceLocation parse(String s){return new ResourceLocation(s);} public String toString(){return value;} }',
'net.minecraft.resources.ResourceKey': 'public record ResourceKey<T>(ResourceLocation location) { public static <T> ResourceKey<T> create(Object registry,ResourceLocation l){return new ResourceKey<T>(l);} }',
'net.minecraft.core.registries.Registries': 'public class Registries { public static final Object DIMENSION=new Object(); }',
'net.minecraft.world.phys.Vec3': '''public class Vec3 {
 public final double x,y,z; public static final Vec3 ZERO=new Vec3(0,0,0);
 public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public Vec3 add(Vec3 v){return add(v.x,v.y,v.z);} public Vec3 add(double a,double b,double c){return new Vec3(x+a,y+b,z+c);}
 public Vec3 subtract(Vec3 v){return new Vec3(x-v.x,y-v.y,z-v.z);} public Vec3 scale(double s){return new Vec3(x*s,y*s,z*s);}
 public double dot(Vec3 v){return x*v.x+y*v.y+z*v.z;} public double distanceToSqr(Vec3 v){Vec3 d=subtract(v);return d.dot(d);}
 public double distanceTo(Vec3 v){return Math.sqrt(distanceToSqr(v));} public Vec3 lerp(Vec3 v,double t){return add(v.subtract(this).scale(t));}
 public String toString(){return x+","+y+","+z;}
}''',
'net.minecraft.world.level.Level': '''import net.minecraft.resources.*; public class Level { public ResourceKey<Level> dim; public Level(ResourceKey<Level> d){dim=d;} public ResourceKey<Level> dimension(){return dim;} public long getGameTime(){return 0;} }''',
'net.minecraft.world.entity.Entity': 'public class Entity {}',
'net.minecraft.world.entity.player.Player': '''import net.minecraft.world.level.Level; import net.minecraft.world.phys.Vec3; import java.util.UUID; public class Player { public Level level(){return null;} public UUID getUUID(){return UUID.randomUUID();} public Vec3 getEyePosition(float t){return Vec3.ZERO;} public Vec3 getEyePosition(){return Vec3.ZERO;} }''',
'net.minecraft.client.multiplayer.ClientLevel': '''import net.minecraft.world.level.Level; import net.minecraft.resources.ResourceKey; import net.minecraft.world.entity.Entity; public class ClientLevel extends Level { public ClientLevel(ResourceKey<Level> d){super(d);} public Iterable<Entity> entitiesForRendering(){return java.util.List.of();} }''',
'qouteall.q_misc_util.my_util.DQuaternion': 'public class DQuaternion {public double x,y,z,w=1;}',
'qouteall.imm_ptl.core.portal.Portal': '''import net.minecraft.world.level.Level; import net.minecraft.resources.ResourceKey; import net.minecraft.world.phys.Vec3; import java.util.UUID; import qouteall.q_misc_util.my_util.DQuaternion;
public class Portal extends net.minecraft.world.entity.Entity {
public boolean isRemoved(){return false;} public UUID getUUID(){return UUID.randomUUID();} public Level level(){return null;}
public ResourceKey<Level> getDestDim(){return null;} public Vec3 getOriginPos(){return Vec3.ZERO;} public Vec3 getDestPos(){return Vec3.ZERO;}
public Vec3 getNormal(){return new Vec3(0,0,1);} public Vec3 getAxisW(){return new Vec3(1,0,0);} public Vec3 getAxisH(){return new Vec3(0,1,0);}
public double getScaling(){return 1;} public double getWidth(){return 2;} public double getHeight(){return 2;}
public DQuaternion getRotationD(){return null;} public Vec3 rayTrace(Vec3 a,Vec3 b){return null;}
}''',
'qouteall.imm_ptl.core.portal.PortalExtension': 'public class PortalExtension {public Portal reversePortal; public static PortalExtension get(Portal p){return new PortalExtension();}}',
'qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage': 'public class GlobalPortalStorage { public static java.util.List<qouteall.imm_ptl.core.portal.Portal> getGlobalPortals(net.minecraft.world.level.Level l){return java.util.List.of();} }',
'qouteall.imm_ptl.core.ClientWorldLoader': 'public class ClientWorldLoader { public static final java.util.List<net.minecraft.client.multiplayer.ClientLevel> worlds=new java.util.ArrayList<>(); public static java.util.List<net.minecraft.client.multiplayer.ClientLevel> getClientWorlds(){return worlds;} }',
'dev.ryanhcode.sable.sublevel.ClientSubLevel': 'public class ClientSubLevel { public java.util.UUID getUniqueId(){return new java.util.UUID(0,1);} public Pose renderPose(float t){return new Pose();} public static class Pose {public net.minecraft.world.phys.Vec3 transformPosition(net.minecraft.world.phys.Vec3 v){return v;}} }',
'ipl.sable.dim.IplDimAgnostic': 'public class IplDimAgnostic { public static net.minecraft.world.level.Level getParentLevel(dev.ryanhcode.sable.sublevel.ClientSubLevel s){return null;} }',
'ipl.sable.client.IplParentDimSync': 'public class IplParentDimSync { public static java.util.List<ipl.sable.transit.IplGrabLink> visibleGrabChain(java.util.UUID s,int frame,java.util.List<ipl.sable.transit.IplGrabLink> l){return l;} public static boolean hasPendingHandoff(java.util.UUID u){return false;} }',
'ipl.sable.client.IplGrabChainClient': 'public class IplGrabChainClient {public static int chainFrame(java.util.UUID o,java.util.UUID s){return 0;} public static java.util.List<ipl.sable.transit.IplGrabLink> chainFor(java.util.UUID o,java.util.UUID s){return java.util.List.of();} }',
'ipl.sable.client.IplStraddleSessionStore': 'public class IplStraddleSessionStore {public static java.util.List<qouteall.imm_ptl.core.portal.Portal> resolveAllRenderPortals(dev.ryanhcode.sable.sublevel.ClientSubLevel s){return java.util.List.of();}}',
}
STUBS.update({
'com.mojang.blaze3d.systems.RenderSystem': 'public class RenderSystem {public static org.joml.Matrix4f view=new org.joml.Matrix4f(); public static org.joml.Matrix4f getModelViewMatrix(){return view;}}',
'qouteall.imm_ptl.core.CHelper': 'public class CHelper {public static net.minecraft.world.phys.Vec3 camera=net.minecraft.world.phys.Vec3.ZERO; public static net.minecraft.world.phys.Vec3 getCurrentCameraPos(){return camera;}}',
'qouteall.q_misc_util.my_util.Plane': 'public record Plane(net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 normal) {}',
'qouteall.imm_ptl.core.render.context_management.PortalRendering': 'public class PortalRendering {public static boolean active=true; public static qouteall.q_misc_util.my_util.Plane plane; public static boolean isRendering(){return active;} public static qouteall.q_misc_util.my_util.Plane getActiveClippingPlane(){return plane;}}',
})
TEST = '''
import ipl.sable.client.*; import ipl.sable.transit.*; import java.util.*; import org.joml.*;
import net.minecraft.resources.*; import net.minecraft.world.level.Level; import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3; import qouteall.imm_ptl.core.ClientWorldLoader;
public class RouteChecks {
static int checks;
static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
static void close(Vec3 a,Vec3 b,String why){ok(a.distanceToSqr(b)<1e-15,why+": "+a+" != "+b);}
static IplGrabLink link(ResourceKey<Level> a,ResourceKey<Level> b,Vec3 origin,Vec3 dest,Quaterniond q){
return new IplGrabLink(UUID.randomUUID(),a,b,origin,dest,q,1,new Vec3(0,0,1),new Vec3(1,0,0),new Vec3(0,1,0),2,2);}
static IplStaffBeamRoutes.Route route(Level l,Vec3 tip,Vec3 target,List<IplGrabLink> links){return new IplStaffBeamRoutes.Route(l,tip,target,links,new Quaterniond(),new UUID(1,1));}
public static void main(String[] args)throws Exception {
var a=new ResourceKey<Level>(ResourceLocation.parse("test:a")); var b=new ResourceKey<Level>(ResourceLocation.parse("test:b"));
var wa=new ClientLevel(a); var wb=new ClientLevel(b); ClientWorldLoader.worlds.add(wa);ClientWorldLoader.worlds.add(wb);
var hop=link(a,b,Vec3.ZERO,new Vec3(100,0,0),new Quaterniond());
var target=new Vec3(100,0,-10); var eye=new Vec3(0,0,0.2); var tip=new Vec3(0,0,-0.4);
var original=route(wa,tip,target,List.of(hop)); var advanced=IplStaffBeamRoutes.advancePastStaffTip(original,eye);
ok(advanced.staffLevel()==wb,"tip changes world");ok(advanced.links().isEmpty(),"consumed prefix");
close(advanced.staffStart(),new Vec3(100,0,-0.4),"tip transformed");ok(original.links().size()==1,"canonical chain untouched");
var runs=IplStaffBeamRoutes.runs(advanced);ok(runs.size()==1,"no backward source segment");
close(runs.get(0).vertices().get(0).point(),advanced.staffStart(),"run starts at actual tip");
ok(IplStaffBeamRoutes.advancePastStaffTip(route(wa,new Vec3(2,0,-0.4),target,List.of(hop)),new Vec3(2,0,.2)).links().size()==1,"finite aperture: no side crossing");
ok(IplStaffBeamRoutes.advancePastStaffTip(route(wa,new Vec3(0,0,.1),target,List.of(hop)),eye).links().size()==1,"no early crossing");
ClientWorldLoader.worlds.remove(wb);ok(IplStaffBeamRoutes.advancePastStaffTip(original,eye)==original,"unloaded destination safe");ClientWorldLoader.worlds.add(wb);
// The target must unfold past the surface, not collapse to an aperture aim point.
var endpoint=IplStaffBeamRoutes.class.getDeclaredMethod("endpointInFrame",IplStaffBeamRoutes.Route.class,int.class);endpoint.setAccessible(true);
close((Vec3)endpoint.invoke(null,original,0),new Vec3(0,0,-10),"window aim");
var unconsumed=route(wa,new Vec3(0,0,1),target,List.of(hop)); var split=IplStaffBeamRoutes.runs(unconsumed);
ok(split.size()==2,"two physical runs");var seamA=split.get(0).vertices().get(split.get(0).vertices().size()-1);var seamB=split.get(1).vertices().get(0);
close(hop.transform(seamA.point()),seamB.point(),"same physical seam");ok(seamA.aperture()&&seamB.aperture(),"both seam vertices pinned");
ok(seamA.fraction()==seamB.fraction(),"shared noise phase");
// Folded beams must sample the LIVE upstream node count, not a second density estimate.
for (int n : new int[]{8, 11, 23}) {
 var live=IplStaffBeamRoutes.runs(unconsumed,n);
 for (int node=1;node<n;node++) {
   double fraction=node/(double)n;
   boolean present=false;
   for(var run:live) for(var vertex:run.vertices()) {
     if(java.lang.Math.abs(vertex.fraction()-fraction)<1e-8) present=true;
   }
   // A sample coincident with the doorway is represented by the pinned seam,
   // not an additional noisy vertex on either side of the mathematical cut.
   ok(present,"all live upstream sample indices retained, including seam-coincident nodes");
 }

}
// Same-dimension loops are coordinate-frame changes too.
var loop=link(a,a,Vec3.ZERO,new Vec3(100,0,0),new Quaterniond());
ok(IplStaffBeamRoutes.advancePastStaffTip(route(wa,tip,target,List.of(loop)),eye).links().isEmpty(),"same-dimension tip crossing");
var random=new java.util.Random(530);
for(int i=0;i<1000;i++){
var q=new Quaterniond().rotateXYZ(random.nextDouble(),random.nextDouble(),random.nextDouble());
var l=link(a,b,new Vec3(3,4,5),new Vec3(-90,20,70),q);var p=new Vec3(random.nextDouble()*100,random.nextDouble()*100,random.nextDouble()*100);
close(l.inverseTransform(l.transform(p)),p,"random rotated frame roundtrip");
var r=route(wa,p,l.transform(new Vec3(0,0,-10)),List.of(l));close((Vec3)endpoint.invoke(null,r,0),new Vec3(0,0,-10),"rotated aim unfolding");
}
// Production late-bind equations must follow the CURRENT recursive layer.
qouteall.imm_ptl.core.CHelper.camera=new Vec3(4,-2,7);
qouteall.imm_ptl.core.render.context_management.PortalRendering.plane=new qouteall.q_misc_util.my_util.Plane(new Vec3(0,0,10),new Vec3(0,0,1));
var oldPlane=ipl.sable.render.IplClipEquationCache.getWorldEq();
ok(oldPlane[3]==-3,"camera-relative clip constant");
qouteall.imm_ptl.core.render.context_management.PortalRendering.plane=new qouteall.q_misc_util.my_util.Plane(new Vec3(10,0,0),new Vec3(1,0,0));
var nextPlane=ipl.sable.render.IplClipEquationCache.getWorldEq();
ok(nextPlane[0]==1 && nextPlane[2]==0 && nextPlane[3]==-6,"recursive layer does not reuse previous plane");
for(int i=0;i<1000;i++) {
var view=new Matrix4f().rotateXYZ(random.nextFloat(),random.nextFloat(),random.nextFloat()).translate(2,-3,4);
com.mojang.blaze3d.systems.RenderSystem.view=view;
var eq=ipl.sable.render.IplClipEquationCache.getEyeEq();
var point=new Vector4f(random.nextFloat()*20,random.nextFloat()*20,random.nextFloat()*20,1);var mapped=view.transform(new Vector4f(point));
double d=eq[0]*mapped.x+eq[1]*mapped.y+eq[2]*mapped.z+eq[3]*mapped.w;
ok(java.lang.Math.abs(d-(point.x-6))<0.00005,"covariant plane transform");
}
qouteall.imm_ptl.core.render.context_management.PortalRendering.active=false;
ok(ipl.sable.render.IplClipEquationCache.getWorldEq()==null && ipl.sable.render.IplClipEquationCache.getEyeEq()==null,"no stale clip at root");
System.out.println("PASS: "+checks+" production Java route checks (isolated frame doubles; no game/GL)");
}
}
'''
with tempfile.TemporaryDirectory(prefix='v053-check-') as temp:
    d=Path(temp)
    files=[]
    for qualified, body in STUBS.items():
        pkg, name=qualified.rsplit('.',1); p=d/Path(qualified.replace('.','/')).with_suffix('.java');p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text('package '+pkg+';\n'+body);files.append(str(p))
    p=d/'RouteChecks.java';p.write_text(TEST);files.append(str(p))
    files += [str(JAVA/p) for p in ['ipl/sable/client/IplStaffBeamRoutes.java','ipl/sable/transit/IplGrabLink.java','ipl/sable/render/IplClipEquationCache.java']]
    javac=str(Path(os.environ.get('JAVA_HOME','/usr'))/'bin/javac'); java=str(Path(javac).with_name('java'))
    subprocess.run([javac,'--release','21','-cp',sys.argv[1],'-d',str(d),*files],check=True)
    subprocess.run([java,'-cp',str(d)+os.pathsep+sys.argv[1],'RouteChecks'],check=True)
