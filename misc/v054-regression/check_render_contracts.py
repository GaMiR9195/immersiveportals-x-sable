#!/usr/bin/env python3
"""Compile production draw orchestration/focus mixin with explicit test doubles.
No Minecraft, Mixin transformer, GPU or visual assertions are run by this test.
Requires JDK 21+. Uses only TemporaryDirectory for generated files.
"""
from pathlib import Path
import os, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT/'src/main/java'
STUBS = {
'org.jetbrains.annotations.Nullable': '@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE,java.lang.annotation.ElementType.METHOD}) public @interface Nullable {}',
'net.minecraft.core.BlockPos': 'public record BlockPos(int x,int y,int z) {}',
'net.minecraft.world.phys.Vec3': 'public record Vec3(double x,double y,double z) {public static final Vec3 ZERO=new Vec3(0,0,0);public Vec3 add(Vec3 p){return new Vec3(x+p.x,y+p.y,z+p.z);}public Vec3 scale(double s){return new Vec3(x*s,y*s,z*s);}public Vec3 lerp(Vec3 p,double t){return new Vec3(x+(p.x-x)*t,y+(p.y-y)*t,z+(p.z-z)*t);}public static Vec3 atCenterOf(net.minecraft.core.BlockPos p){return new Vec3(p.x()+.5,p.y()+.5,p.z()+.5);}}',
'net.minecraft.world.phys.AABB': 'public class AABB {public net.minecraft.world.phys.Vec3 getCenter(){return new net.minecraft.world.phys.Vec3(0,0,0);}}',
'net.minecraft.client.multiplayer.ClientLevel': 'public record ClientLevel(String dimension) {}',
'net.minecraft.client.Minecraft': '''public class Minecraft {static final Minecraft I=new Minecraft(); public static Minecraft getInstance(){return I;} public net.minecraft.client.multiplayer.ClientLevel level; public final Options options=new Options(); public static class Options {public boolean first=true; public Options getCameraType(){return this;} public boolean isFirstPerson(){return first;}}}''',
'net.minecraft.client.Camera': 'public class Camera {public boolean isDetached(){return true;}}',
'net.minecraft.client.renderer.GameRenderer': 'public class GameRenderer {public final net.minecraft.client.Camera camera=new net.minecraft.client.Camera();public net.minecraft.client.Camera getMainCamera(){return camera;}}',
'com.mojang.blaze3d.vertex.PoseStack': 'public class PoseStack {}',
'net.createmod.catnip.render.SuperRenderTypeBuffer': 'public interface SuperRenderTypeBuffer {}',
'net.createmod.catnip.outliner.Outline': 'public class Outline {}',
'net.createmod.catnip.outliner.ChasingAABBOutline': 'public class ChasingAABBOutline extends AABBOutline {}',
'net.createmod.catnip.outliner.AABBOutline': 'public class AABBOutline extends Outline {public net.minecraft.world.phys.AABB getBounds(){return new net.minecraft.world.phys.AABB();}}',
'ipl.sable.duck.IplSubLevelDuck': 'public interface IplSubLevelDuck {net.minecraft.client.multiplayer.ClientLevel ipl$getParentLevel();}',
'dev.ryanhcode.sable.sublevel.ClientSubLevel': '''public class ClientSubLevel implements ipl.sable.duck.IplSubLevelDuck {public net.minecraft.client.multiplayer.ClientLevel parent; public net.minecraft.client.multiplayer.ClientLevel ipl$getParentLevel(){return parent;}}''',
'qouteall.q_misc_util.my_util.Plane': 'public record Plane(String name) {}',
'ipl.sable.client.IplStaffPortalBeamRenderer': 'public class IplStaffPortalBeamRenderer {public static boolean physical;public static IplStaffBeamRoutes.Run run;public static boolean isPhysicalBeamPass(){return physical;}public static IplStaffBeamRoutes.Run getActiveRun(){return run;} public static dev.ryanhcode.sable.sublevel.ClientSubLevel sub; public static dev.ryanhcode.sable.sublevel.ClientSubLevel findHostedSubLevel(net.minecraft.world.phys.Vec3 p){return sub;}}',
'ipl.sable.client.IplClientHostedLookup': '''public class IplClientHostedLookup {public static dev.ryanhcode.sable.sublevel.ClientSubLevel findHostedSubLevel(net.minecraft.world.phys.Vec3 p){return IplStaffPortalBeamRenderer.sub;}public record StraddleProjection(dev.ryanhcode.sable.sublevel.ClientSubLevel sub,Object mappedPose,qouteall.q_misc_util.my_util.Plane destPlane,Object portal){} public static java.util.List<StraddleProjection> images=java.util.List.of();public static java.util.List<StraddleProjection> getStraddleProjectionsInto(net.minecraft.client.multiplayer.ClientLevel l){if(IplStraddleRenderState.active)throw new AssertionError("image computed from override");return images;}}''',
'ipl.sable.client.IplStraddleRenderState': '''public class IplStraddleRenderState {public static boolean active;public static void set(Object a,Object b,Object c,Object d){active=true;}public static void clear(){active=false;}}''',
'ipl.sable.render.SourceClipPortalFinder': '''public class SourceClipPortalFinder {public record ClipDecision(qouteall.q_misc_util.my_util.Plane plane){} public static java.util.List<ClipDecision> cuts=java.util.List.of();public static java.util.List<ClipDecision> findStraddlingPortalPlanes(Object s){return cuts;}}''',
'ipl.sable.render.SubLevelClipUniformPatcher': '''public class SubLevelClipUniformPatcher {public record State(Object equation){} public static Object equation;public static State captureState(){return new State(equation);}public static void restoreState(State s){equation=s.equation();}public static void patchForSubLevel(Object s,Object first,Object second){equation=first;org.lwjgl.opengl.GL11.glEnable(1);org.lwjgl.opengl.GL11.glEnable(2);}}''',
'ipl.sable.client.IplOutlineBuffer': '''public class IplOutlineBuffer implements net.createmod.catnip.render.SuperRenderTypeBuffer,AutoCloseable {public static int made,drawn,closed;public IplOutlineBuffer(){made++;}public void draw(){if(ipl.sable.render.SubLevelClipUniformPatcher.equation==null)throw new AssertionError("flush after clip");drawn++;}public void close(){closed++;}}''',
'org.lwjgl.opengl.GL11': 'public class GL11 {public static boolean[] flags=new boolean[3];public static boolean glIsEnabled(int n){return flags[n];}public static void glEnable(int n){flags[n]=true;}public static void glDisable(int n){flags[n]=false;}}',
'org.lwjgl.opengl.GL30': 'public class GL30 {public static final int GL_CLIP_DISTANCE1=1, GL_CLIP_DISTANCE2=2;}',
'qouteall.imm_ptl.core.render.context_management.WorldRenderInfo': '''public class WorldRenderInfo {public static boolean active; public net.minecraft.client.multiplayer.ClientLevel world; public static WorldRenderInfo top=new WorldRenderInfo();public static boolean isRendering(){return active;}public static WorldRenderInfo getTopRenderInfo(){return top;}}''',
'qouteall.imm_ptl.core.render.context_management.RenderStates': 'public class RenderStates {public static net.minecraft.client.Camera originalCamera;}',
'com.llamalad7.mixinextras.injector.wrapoperation.Operation': '@FunctionalInterface public interface Operation<T>{T call(Object...args);}',

'org.spongepowered.asm.mixin.Shadow': 'public @interface Shadow {boolean remap() default true;}',
'com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod': 'public @interface WrapMethod {String[] method();}',
'ipl.sable.client.IplStaffBeamRoutes': 'public class IplStaffBeamRoutes {public record Vertex(net.minecraft.world.phys.Vec3 point,double fraction,Object noiseRotation,boolean aperture){} public record Run(java.util.List<Vertex> vertices,double totalLength){} public static double knownLengthFor(Object o){return Double.NaN;}public static net.minecraft.world.phys.Vec3 rotate(Object r,net.minecraft.world.phys.Vec3 v){return v;}}',
'net.createmod.catnip.outliner.LineOutline': 'public class LineOutline {public int draws;public net.minecraft.world.phys.Vec3 start,end;public LineOutline set(net.minecraft.world.phys.Vec3 a,net.minecraft.world.phys.Vec3 b){start=a;end=b;return this;}public void render(com.mojang.blaze3d.vertex.PoseStack p,net.createmod.catnip.render.SuperRenderTypeBuffer b,net.minecraft.world.phys.Vec3 c,float t){draws++;}}',
'ipl.sable.mixin.client.IplPhysicsStaffBeamNodeAccessorMixin': 'public interface IplPhysicsStaffBeamNodeAccessorMixin {net.minecraft.world.phys.Vec3 ipl$getPreviousPosition();net.minecraft.world.phys.Vec3 ipl$getPosition();}',
'org.spongepowered.asm.mixin.Mixin': '@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE) public @interface Mixin {String[] targets() default {};boolean remap() default true;}',
'org.spongepowered.asm.mixin.Pseudo': 'public @interface Pseudo {}',
'org.spongepowered.asm.mixin.injection.At': 'public @interface At {String value();String target() default "";}',
'org.spongepowered.asm.mixin.injection.Inject': 'public @interface Inject {String[] method();At at();boolean cancellable() default false;int require() default -1;boolean remap() default true;}',
'com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation': 'public @interface WrapOperation {String[] method();org.spongepowered.asm.mixin.injection.At at();int require() default -1;}',
'org.spongepowered.asm.mixin.injection.callback.CallbackInfo': 'public class CallbackInfo {public boolean cancelled;public void cancel(){cancelled=true;}}',
}
TEST = '''
import ipl.sable.client.*;import ipl.sable.duck.*;import ipl.sable.render.*;
import net.minecraft.client.*;import net.minecraft.client.multiplayer.*;import net.createmod.catnip.outliner.*;import net.createmod.catnip.render.*;
import org.lwjgl.opengl.*;import qouteall.q_misc_util.my_util.*;import qouteall.imm_ptl.core.render.context_management.*;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.*;import java.util.function.*;
public class RenderChecks {
static int checks;static void ok(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
static final class Cluster extends Outline implements IplClusterOutlineAnchor {public net.minecraft.core.BlockPos ipl$getOutlineAnchor(){return new net.minecraft.core.BlockPos(1,2,3);}}
public static void main(String[] args)throws Exception {
var root=new ClientLevel("root");var other=new ClientLevel("other");Minecraft.getInstance().level=root;
var sub=new dev.ryanhcode.sable.sublevel.ClientSubLevel();sub.parent=root;IplStaffPortalBeamRenderer.sub=sub;
SuperRenderTypeBuffer global=new SuperRenderTypeBuffer(){};int[] calls={0};
IplHostedOutlineRenderer.render(new Outline(),global,b->{calls[0]++;ok(b==global,"unrelated uses supplied buffer");});ok(calls[0]==1,"unrelated exactly once");
calls[0]=0;IplHostedOutlineRenderer.render(new Cluster(),global,b->{calls[0]++;ok(b==global,"plain selection unchanged");});ok(calls[0]==1,"plain native once");
var source=new Plane("source");var dest=new Plane("dest");var source2=new Plane("source2");
SourceClipPortalFinder.cuts=List.of(new SourceClipPortalFinder.ClipDecision(source));
var image=new IplClientHostedLookup.StraddleProjection(sub,new Object(),dest,new Object());IplClientHostedLookup.images=List.of(image);
Object outer=new Object();SubLevelClipUniformPatcher.equation=outer;GL11.glEnable(1);GL11.glDisable(2);
List<Boolean> imageFlags=new ArrayList<>();List<Object> equations=new ArrayList<>();
Consumer<SuperRenderTypeBuffer> draw=b->{ok(b!=global,"isolated buffer");ok(GL11.glIsEnabled(1)&&GL11.glIsEnabled(2),"clips active while emitting");imageFlags.add(IplStraddleRenderState.active);equations.add(SubLevelClipUniformPatcher.equation);};
IplHostedOutlineRenderer.render(new Cluster(),global,draw);
ok(imageFlags.equals(List.of(false,true)),"same-dimension draws BOTH halves");ok(equations.equals(List.of(source,dest)),"complementary world planes");
ok(SubLevelClipUniformPatcher.equation==outer,"outer equation restored");ok(GL11.glIsEnabled(1)&&!GL11.glIsEnabled(2),"outer GL state restored");ok(!IplStraddleRenderState.active,"pose override released");ok(IplOutlineBuffer.drawn==2&&IplOutlineBuffer.closed==2,"both buffers flushed then closed");
WorldRenderInfo.active=true;WorldRenderInfo.top.world=other;imageFlags.clear();equations.clear();
IplHostedOutlineRenderer.render(new Cluster(),global,draw);ok(imageFlags.equals(List.of(true)),"foreign portal pass image only");
IplClientHostedLookup.images=List.of();calls[0]=0;IplHostedOutlineRenderer.render(new Cluster(),global,b->calls[0]++);ok(calls[0]==0,"no foreign source leak");
WorldRenderInfo.active=false;IplClientHostedLookup.images=List.of(image,image);imageFlags.clear();equations.clear();
SourceClipPortalFinder.cuts=List.of(new SourceClipPortalFinder.ClipDecision(source),new SourceClipPortalFinder.ClipDecision(source2));
IplHostedOutlineRenderer.render(new ChasingAABBOutline(),global,draw);ok(imageFlags.equals(List.of(false,true,true)),"AABB uses all mapped instances");
WorldRenderInfo.active=true;WorldRenderInfo.top.world=other;int closed=IplOutlineBuffer.closed;
try {IplHostedOutlineRenderer.render(new Cluster(),global,b->{throw new IllegalStateException("sentinel");});throw new AssertionError("exception lost");}catch(IllegalStateException expected){}
ok(IplOutlineBuffer.closed==closed+1,"buffer released after exception");ok(!IplStraddleRenderState.active,"pose restored after exception");ok(SubLevelClipUniformPatcher.equation==outer,"equation restored after exception");ok(GL11.glIsEnabled(1)&&!GL11.glIsEnabled(2),"GL restored after exception");
// Exercise actual focus-mixin callbacks, not a rewritten copy of their condition.
Class<?> focus=Class.forName("ipl.sable.mixin.client.IplStaffFocusMixin");
var mode=focus.getDeclaredMethod("ipl$physicalCameraMode",Camera.class,Operation.class);mode.setAccessible(true);
Operation<Boolean> fakeDetached=a->true;
Minecraft.getInstance().options.first=true;ok(!(boolean)mode.invoke(null,new Camera(),fakeDetached),"first-person survives IP detached override");
Minecraft.getInstance().options.first=false;ok((boolean)mode.invoke(null,new Camera(),fakeDetached),"F5 remains third-person");
var camera=focus.getDeclaredMethod("ipl$rootCamera",net.minecraft.client.renderer.GameRenderer.class,Operation.class);camera.setAccessible(true);
var renderer=new net.minecraft.client.renderer.GameRenderer();RenderStates.originalCamera=new Camera();
Operation<Camera> cameraOp=a->renderer.getMainCamera();WorldRenderInfo.active=true;
ok(camera.invoke(null,renderer,cameraOp)==RenderStates.originalCamera,"recursive focus uses physical camera");WorldRenderInfo.active=false;
ok(camera.invoke(null,renderer,cameraOp)==renderer.camera,"root camera unchanged");

var beam=new ipl.sable.mixin.client.IplStaffBeamMixin(){};var beamClass=ipl.sable.mixin.client.IplStaffBeamMixin.class;
var wrap=beamClass.getDeclaredMethod("ipl$renderRun",net.minecraft.world.phys.Vec3.class,net.minecraft.world.phys.Vec3.class,com.mojang.blaze3d.vertex.PoseStack.class,SuperRenderTypeBuffer.class,net.minecraft.world.phys.Vec3.class,float.class,Operation.class);wrap.setAccessible(true);
var start=new net.minecraft.world.phys.Vec3(0,0,0);var end=new net.minecraft.world.phys.Vec3(8,0,0);var poses=new com.mojang.blaze3d.vertex.PoseStack();
IplStaffPortalBeamRenderer.run=new IplStaffBeamRoutes.Run(List.of(new IplStaffBeamRoutes.Vertex(start,0,null,false),new IplStaffBeamRoutes.Vertex(end,1,null,false)),8);
int[] originals={0};Operation<Void> stock=a->{originals[0]++;ok(a[0]==start&&a[1]==end&&a[2]==poses&&a[3]==global,"stock renderer arguments unchanged");return null;};
IplStaffPortalBeamRenderer.physical=false;wrap.invoke(beam,start,end,poses,global,start,.5f,stock);ok(originals[0]==0,"outside owned beam stage suppressed");
IplStaffPortalBeamRenderer.physical=true;wrap.invoke(beam,start,end,poses,global,start,.5f,stock);ok(originals[0]==1,"unbroken beam delegates to original render");
var line=new LineOutline();var lineField=beamClass.getDeclaredField("line");lineField.setAccessible(true);lineField.set(beam,line);
var nodesField=beamClass.getDeclaredField("nodes");nodesField.setAccessible(true);
ipl.sable.mixin.client.IplPhysicsStaffBeamNodeAccessorMixin node=new ipl.sable.mixin.client.IplPhysicsStaffBeamNodeAccessorMixin(){public net.minecraft.world.phys.Vec3 ipl$getPreviousPosition(){return new net.minecraft.world.phys.Vec3(0,1,0);}public net.minecraft.world.phys.Vec3 ipl$getPosition(){return new net.minecraft.world.phys.Vec3(0,3,0);}};
nodesField.set(beam,List.of(node,node,node,node));var radius=beamClass.getDeclaredField("currentNodeRadius");radius.setAccessible(true);radius.setDouble(beam,.2);
var seam=new net.minecraft.world.phys.Vec3(2,0,0);var sample=new net.minecraft.world.phys.Vec3(4,0,0);
IplStaffPortalBeamRenderer.run=new IplStaffBeamRoutes.Run(List.of(new IplStaffBeamRoutes.Vertex(seam,.25,null,true),new IplStaffBeamRoutes.Vertex(sample,.5,null,false)),8);
wrap.invoke(beam,seam,sample,poses,global,start,.5f,stock);
ok(originals[0]==1,"portal run uses adapter only");ok(line.draws==1,"one segment emitted");ok(line.start.equals(seam),"seam stays on aperture");ok(line.end.equals(new net.minecraft.world.phys.Vec3(4,.4,0)),"stock node interpolation and radius retained");
System.out.println("PASS: "+checks+" production draw/focus contract assertions (test doubles; no game/GL/Mixin transformation)");
}}
'''
with tempfile.TemporaryDirectory(prefix='ipl-v054-') as d:
    d=Path(d); paths=[]
    for fq,source in STUBS.items():
        package,cls=fq.rsplit('.',1); p=d/Path(fq.replace('.','/')).with_suffix('.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package '+package+';\n'+source);paths.append(str(p))
    test=d/'RenderChecks.java';test.write_text(TEST);paths.append(str(test))
    paths += [str(JAVA/p) for p in ['ipl/sable/client/IplHostedOutlineRenderer.java','ipl/sable/duck/IplClusterOutlineAnchor.java','ipl/sable/mixin/client/IplStaffFocusMixin.java','ipl/sable/mixin/client/IplStaffBeamMixin.java']]
    home=Path(os.environ['JAVA_HOME'])/'bin' if 'JAVA_HOME' in os.environ else Path('')
    subprocess.run([str(home/'javac') if str(home)!='.' else 'javac','--release','21','-d',str(d/'classes'),*paths],check=True)
    subprocess.run([str(home/'java') if str(home)!='.' else 'java','-cp',str(d/'classes'),'RenderChecks'],check=True)
