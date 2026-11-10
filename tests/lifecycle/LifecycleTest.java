package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static ipl.sable.transit.IplTransitSettlement.Bounds;

/** Real lifecycle/snapshot/map/mapping methods on tiny Minecraft API fixtures, without JNI. */
public final class LifecycleTest {
    private static int checks;
    private static long serial;
    private static ServerLevel level;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static UUID id() { return new UUID(0, ++serial); }
    private static void reset() {
        IplAtlasStraddleSession.reset();
        level = new ServerLevel(new MinecraftServer(), "test");
    }
    private static Portal portal(double x, double shift) {
        return new Portal(id(), level, level, new Vec3(x,0.5,0.5), new Vec3(x+shift,0.5,0.5));
    }
    private static ServerSubLevel ship(double x, double y, int... blocks) {
        ServerSubLevel ship = new ServerSubLevel(id(),level);
        ship.logicalPose().position().set(x,y,0);
        for (int block : blocks) ship.getPlot().put(block,0,0);
        return ship;
    }
    private static int open(ServerSubLevel ship, int parent, Portal portal) {
        int node = IplPieceTracker.openSession(IplPieceTracker.trackForContext(ship),ship,parent,portal);
        check(node > 0, "test path opened");
        return node;
    }
    private static void label(ServerSubLevel ship, int node, int... blocks) {
        var body=IplPieceTracker.trackForContext(ship);
        for (int block:blocks) body.map.setLabel(block,0,0,node);
    }
    private static int label(ServerSubLevel ship, int block) {
        var body=IplPieceTracker.tracked(ship);
        return body==null?0:body.map.label(block,0,0);
    }
    private static int root(ServerSubLevel ship, Portal portal) {
        return IplPieceTracker.tracked(ship).childOf(0,portal.getUUID());
    }
    private static void settle(int tick, ServerSubLevel... ships) { IplSessionLifecycle.settle(List.of(ships),tick); }

    private static void stickAndWithdraw() {
        reset();
        Portal p=portal(0,100);
        ServerSubLevel ship=ship(-5,0,0,1,2,3);
        int node=open(ship,0,p);label(ship,node,0,1,2);
        settle(1,ship);
        for(int x=0;x<4;x++) check(label(ship,x)==node,"native sliver cannot keep source ground after complete transit");
        check(IplPieceTracker.tracked(ship).pendingFlip!=null,"stick is ready without a detach timeout");
        check(IplPieceTracker.tracked(ship).map.edges().length==0,"publication, not settlement, owns edge upload");
        ship.logicalPose().position().x=2;
        settle(2,ship);
        check(!IplPieceTracker.tracked(ship).hasSessions(),"fully withdrawn stick closes session");
        check(IplPieceTracker.tracked(ship).map.isEmpty(),"withdrawal clears historical and air labels");
        check(IplStraddleSessionSync.active.isEmpty(),"withdrawal also clears advertisement registry");
        check(IplPieceTracker.tracked(ship).pendingFlip==null,"withdrawal never teleports the native frame");
    }

    private static void gapsAreNotTimeouts() {
        reset();Portal p=portal(0,100);
        ServerSubLevel u=ship(0,0,-3,3);int node=open(u,0,p);label(u,node,-3);
        for(int tick=1;tick<=200;tick++) settle(tick,u);
        // One body whose two parts meet only around the frame (a U/ring around the portal) is
        // not cut by it: the minority side rejoins, the session closes, nothing teleports.
        check(label(u,-3)==0&&label(u,3)==0,"U around an empty aperture rejoins its majority frame");
        check(!IplPieceTracker.tracked(u).hasSessions(),"U around an empty aperture does not hold an eternal session");
        check(IplPieceTracker.tracked(u).pendingFlip==null,"U does not flip through an empty bridge");
        reset();p=portal(0,100);
        ServerSubLevel arm=ship(0,0,-1,0,1);node=open(arm,0,p);label(arm,node,-1,0);
        for(int tick=1;tick<=20;tick++) settle(tick,arm);
        check(label(arm,-1)==node&&label(arm,1)==0,"material in the aperture keeps a real split");
        check(IplPieceTracker.tracked(arm).hasSessions(),"a body cut by the aperture keeps its session");
        reset();p=portal(0,100);
        ServerSubLevel a=ship(-3,0,0),b=ship(3,0,0);
        node=open(a,0,p);label(a,node,0);
        settle(1,a,b);
        check(IplPieceTracker.tracked(b)!=null&&root(b,p)>0,"air gap holds a common path on both joint links");
        check(label(b,0)==0,"joining a shared context does not fabricate the mate's material crossing");
        check(label(a,0)==node,"already transferred chain link keeps its image while mate is in front");
        check(IplPieceTracker.tracked(a).pendingFlip!=null,"individual choice can be ready, group gate still waits for mate");
        b.logicalPose().position().x=-2;
        settle(2,a,b);
        check(label(b,0)>0,"formerly untouched native mate joins the completed group frame");
        var ca=IplPieceTracker.tracked(a).pendingFlip;var cb=IplPieceTracker.tracked(b).pendingFlip;
        check(ca!=null&&cb!=null&&ca.portal()==cb.portal(),"chain members become ready through the SAME root in one round");
        check(IplStraddleSessionSync.active.size()==2,"one coherent image path is advertised per chain member");
    }

    private static void emptyJointConnector() {
        reset();Portal p=portal(0,100);
        ServerSubLevel a=ship(-4,0,0),empty=ship(-3,0);
        int node=open(a,0,p);label(a,node,0);settle(1,a,empty);
        check(IplPieceTracker.tracked(empty).pendingFlip!=null,"zero-block joint member cannot deadlock complete group");
        check(IplPieceTracker.tracked(empty).pendingFlip.portal()==p,"empty member has group's exact root choice");
    }

    private static void siblingOffsetsAndEquivalentFrames() {
        reset();Portal a=portal(0,100),b=portal(0,200);
        ServerSubLevel s=ship(0,10,-3,-2,-1,0,1,2);
        int na=open(s,0,a),nb=open(s,0,b);label(s,na,-3,-2,-1,0);label(s,nb,1);
        settle(1,s);
        for(int x=-3;x<=2;x++) check(label(s,x)==na,"fully lateral completed body has no detached sibling offset");
        check(IplPieceTracker.tracked(s).keys[nb]==null,"obsolete sibling session is removed, not just hidden");
        check(IplStraddleSessionSync.active.size()==1,"no stale sibling client entry remains");
        check(IplPieceTracker.tracked(s).pendingFlip!=null,"completed lateral path is not held by stale plane cuts");
        reset();a=portal(0,100);b=portal(0,100);Portal c=portal(0,200);
        s=ship(0,10,-3,-2,-1,0,1,2,3);
        na=open(s,0,a);nb=open(s,0,b);int nc=open(s,0,c);
        label(s,na,-3,-2);label(s,nb,-1,0);label(s,nc,1,2,3);
        settle(1,s);
        for(int x=-3;x<=3;x++) check(label(s,x)==na,"equivalent 2+2 paths beat unrelated 3-block path");
        check(IplPieceTracker.tracked(s).pendingFlip.portal()==a,"equivalent paths select deterministic canonical portal");
    }

    private static void nativeVotesAndLoops() {
        reset();Portal p=portal(0,100);
        ServerSubLevel s=ship(0,10,-3,-2,-1,0,1);int node=open(s,0,p);label(s,node,-3);
        settle(1,s);
        check(!IplPieceTracker.tracked(s).hasSessions()&&IplPieceTracker.tracked(s).map.isEmpty(),
            "clear lateral majority-native body cleans up isolated historical fragment");
        reset();Portal a=portal(0,100),b=portal(100,-100);
        s=ship(-4,0,0,1);int na=open(s,0,a),nb=open(s,na,b);label(s,nb,0,1);
        settle(1,s);
        check(IplPieceTracker.tracked(s).map.isEmpty()&&!IplPieceTracker.tracked(s).hasSessions(),"identity portal loop settles directly to native");
        check(IplPieceTracker.tracked(s).pendingFlip==null,"identity loop is not repeatedly teleported in later rounds");
    }

    private static void nestedHistoryAndReturn() {
        reset();Portal a=portal(0,100),b=portal(110,20);
        ServerSubLevel s=ship(2,0,0,1);int na=open(s,0,a),nb=open(s,na,b);label(s,nb,0,1);
        settle(1,s);
        check(label(s,0)==nb&&IplPieceTracker.tracked(s).keys[na]!=null,
            "ancestor's old entry side does not cancel a valid deeper completed frame");
        check(IplPieceTracker.tracked(s).pendingFlip.node()==na,"nested completion still hands off one root at a time");
        reset();a=portal(0,100);b=portal(100,20);
        s=ship(2,0,0,1);na=open(s,0,a);nb=open(s,na,b);label(s,nb,0,1);
        settle(1,s);
        check(IplPieceTracker.tracked(s).map.isEmpty()&&!IplPieceTracker.tracked(s).hasSessions(),
            "reverse return cascades deepest-first without leaving a nonempty prefix");
    }

    private static void failedOpenRollsBackWholeCommit() {
        reset();Portal p=portal(0,100);
        ServerSubLevel a=ship(0,10,-4,-3,-2,-1,0,1,2,3),b=ship(0,10,-2,1),c=ship(0,10,-2,1);
        int node=open(a,0,p);label(a,node,-4,-3,-2,-1,0,1,2);
        IplRapierNatives.failBody=c.getRuntimeId();
        settle(1,a,b,c);
        // A has the group majority: this must attempt the new paths, not simply choose native.
        check(label(a,-2)==node&&label(a,3)==0,"failed native image open keeps original leader labels");
        check(label(b,-2)==0&&label(c,-2)==0,"failed group commit leaves mate material native");
        check(IplAtlasStraddleSession.sessionKeysFor(b.getUniqueId()).isEmpty(),"earlier newly opened mate path rolls back");
        check(IplStraddleSessionSync.active.size()==1,"rollback has no stale client path");
        check(IplRapierNatives.opened==2&&IplRapierNatives.failed==1,"rollback test really opened then undid a mate path");
        check(IplRapierNatives.images.size()==1,"rollback removes the actual native image handle as well");
        IplRapierNatives.failBody=-1;
    }

    private static void reusedPieceHasNoOldFrameHistory() {
        reset();Portal a=portal(0,100),b=portal(100,20),c=portal(0,300);
        ServerSubLevel s=ship(-4,0,0);int na=open(s,0,a),nb=open(s,na,b);label(s,nb,0);
        var body=IplPieceTracker.tracked(s);
        IplPieceTracker.settled(body,s,1,nb,false);
        check(body.sampledMappings.size()==2&&body.watches.size()==2,"nested path has two observed frame histories");
        IplPieceTracker.endSubtree(body,level.getServer(),na,"test-freed");
        check(body.sampledMappings.isEmpty(),"freeing an id discards old portal-isometry interpolation");
        check(body.watches.size()==1,"discarded parent frame loses child aperture watches; native watch stays valid");
        int reused=open(s,0,c);
        check(reused==na,"test actually reuses the numeric piece id for another portal");
        check(!body.sampledMappings.containsKey(reused),"reused id cannot interpolate from the previous path's offset");
        check(label(s,0)==0,"old descendant material and registry are reconciled before id reuse");
    }

    private static void promotedFrameHasGeometryImmediately() {
        reset();Portal a=portal(0,100),b=portal(100,20);
        ServerSubLevel s=ship(-4,0,0,1);int na=open(s,0,a),nb=open(s,na,b);label(s,nb,0,1);
        var body=IplPieceTracker.tracked(s);
        IplPieceTracker.settled(body,s,1,nb,false);
        var promotions=IplAtlasStraddleSession.promotionsThrough(body.keys[na]);
        var mapped=IplStraddlePoseMap.StraddleMapping.of(a).mapPose(s.logicalPose());
        s.logicalPose().position().set(mapped.position());s.logicalPose().orientation().set(mapped.orientation());
        IplAtlasStraddleSession.clearShip(s.getUniqueId(),"test-root-flip");
        var opened=IplAtlasStraddleSession.promote(s,promotions);
        IplPieceTracker.afterFlip(s,na,new ChunkPos(0,0),promotions,opened);
        check(label(s,0)==nb&&body.parent[nb]==0,"real afterFlip keeps promoted material and re-roots its parent");
        check(body.watches.size()==1&&body.sampledMappings.size()==1,"promoted aperture is seeded before next physics sample");
        check(body.map.edges().length==1&&body.map.edges()[0].child==nb,"afterFlip publishes actual promoted contact geometry immediately");
        var nativeEdges=IplRapierNatives.edges.get(s.getRuntimeId());
        check(nativeEdges!=null&&nativeEdges.length==32&&nativeEdges[1]==nb,"native receives promoted full aperture even at per-tick flip cap");
        check(body.pendingFlip==null,"old root's pending choice cannot survive handoff");
        check(IplRapierNatives.images.size()==1,"root flip leaves only its promoted native image");
    }

    private static void invalidatedSubtreeReconcilesAllStores() {
        reset();Portal a=portal(0,100),b=portal(100,20);
        ServerSubLevel s=ship(-4,0,0);int na=open(s,0,a),nb=open(s,na,b);label(s,nb,0);
        var body=IplPieceTracker.tracked(s);
        IplAtlasStraddleSession.clear(body.keys[na],"test-invalidated");
        IplPieceTracker.reconcile(body,s);
        check(label(s,0)==0&&!body.hasSessions(),"invalidated parent returns descendants to nearest live frame");
        check(IplStraddleSessionSync.active.isEmpty(),"invalidated native registry also retires client advertisements");
        check(IplRapierNatives.images.isEmpty(),"invalidated subtree releases every real image handle");
    }

    private static void bodyReplacementAndPortalRetarget() {
        reset();Portal p=portal(0,100);
        ServerSubLevel old=ship(-4,0,0);int node=open(old,0,p);label(old,node,0);
        var body=IplPieceTracker.tracked(old);
        IplPieceTracker.settled(body,old,1,node,false);
        body.pushedLevel=level;body.pushedScene=level.scene;body.pushedBody=old.getRuntimeId();body.nativeHasPieces=true;
        ServerSubLevel replacement=new ServerSubLevel(old.getUniqueId(),level);
        replacement.logicalPose().position().x=800;replacement.getPlot().put(0,0,0);
        IplPieceTracker.rebindObservation(body,replacement);
        IplAtlasStraddleSession.refreshShip(replacement);
        IplPieceTracker.reconcile(body,replacement);
        check(body.owner==replacement&&body.sampledPose.position().x==800,"same UUID/new native body starts at its own observed pose");
        check(body.sampledMappings.isEmpty()&&body.watches.isEmpty(),"body replacement cannot sweep from former image offsets");
        check(body.map.isEmpty()&&!body.hasSessions(),"old owner's sessions and labels cannot survive UUID reuse");
        check(IplRapierNatives.images.isEmpty()&&IplRapierNatives.cleared==1,"replacement releases old native image and piece mask");

        reset();p=portal(0,100);ServerSubLevel s=ship(-4,0,0);node=open(s,0,p);label(s,node,0);
        var key=IplPieceTracker.tracked(s).keys[node];
        p.width=7;
        IplAtlasStraddleSession.refreshShip(s);
        check(IplStraddleSessionSync.widths.get(key)==7,"same-isometry aperture resize is still advertised to clients");
        check(IplRapierNatives.updated==0,"rectangle-only resize does not unnecessarily reframe the native image");
        p.dest=new ServerLevel(level.getServer(),"other");
        IplAtlasStraddleSession.refreshShip(s);IplPieceTracker.reconcile(IplPieceTracker.tracked(s),s);
        check(!IplPieceTracker.tracked(s).hasSessions()&&IplRapierNatives.images.isEmpty(),"retargeted destination invalidates image in its former chart");

        reset();p=portal(0,100);s=ship(-4,0,0);node=open(s,0,p);label(s,node,0);
        p.teleportable=false;
        IplAtlasStraddleSession.refreshShip(s);IplPieceTracker.reconcile(IplPieceTracker.tracked(s),s);
        check(!IplPieceTracker.tracked(s).hasSessions()&&IplRapierNatives.images.isEmpty(),"deactivated portal retires existing material frame");
    }

    private static void groupOrderAndRepeatedCompletion() {
        for(int iteration=0;iteration<60;iteration++) {
            reset();Portal a=portal(0,100),b=portal(0,200);
            ServerSubLevel x=ship(0,10,-3,-2,-1,0,1),y=ship(0,10,-2,-1,0);
            int ax=open(x,0,a),by=open(y,0,b);label(x,ax,-3,-2,-1,0);label(y,by,-2,-1,0);
            if((iteration&1)==0)settle(1,x,y);else settle(1,y,x);
            check(IplPieceTracker.tracked(x).pendingFlip.portal()==a&&IplPieceTracker.tracked(y).pendingFlip.portal()==a,
                "joint frame vote is component-wide and independent of iteration order");
            int nx=root(x,a),ny=root(y,a);
            settle(2,x,y);
            check(root(x,a)==nx&&root(y,a)==ny,"repeated clear settlement does not reallocate session ids");
            check(IplStraddleSessionSync.active.size()==2,"repeated clear settlement cannot accumulate images");
        }
    }

    private static IplPieceMap.Aperture rect(Vector3d o,Quaterniond q,double w,double h) {
        Vector3d x=q.transform(new Vector3d(1,0,0)),y=q.transform(new Vector3d(0,1,0)),z=q.transform(new Vector3d(0,0,1));
        return new IplPieceMap.Aperture(o.x,o.y,o.z,z.x,z.y,z.z,x.x,x.y,x.z,w,y.x,y.y,y.z,h);
    }
    private static void finiteGeometryAndRotation() {
        reset();var a=IplPieceMap.Aperture.of(portal(0,100));
        check(IplTransitSettlement.intersects(new Bounds(-2,0,0,2,1,1),a),"group envelope spans finite aperture");
        check(!IplTransitSettlement.intersects(new Bounds(-2,10,0,2,11,1),a),"group envelope misses finite side of portal");
        check(IplTransitSettlement.intersects(new Bounds(0,0,0,2,1,1),a),"tangent outer voxel face is not prematurely clear");
        check(IplTransitSettlement.behind(new Bounds(-3,0,0,-1,1,1),a),"all voxel outer faces behind plane");
        check(!IplTransitSettlement.behind(new Bounds(-1,0,0,0,1,1),a),"centre behind but trailing face tangent is not complete");
        Portal p=portal(1,100);p.width=10;p.height=10;
        Pose3d start=new Pose3d(),end=new Pose3d();
        start.orientation().rotationZ(Math.toRadians(-80));end.orientation().rotationZ(Math.toRadians(80));
        var before=IplPieceMap.Aperture.of(p).toPlot(start);var after=IplPieceMap.Aperture.of(p).toPlot(end);
        check(before.side(2,0,0)<0&&after.side(2,0,0)<0,"rotation regression has same side at both endpoints");
        check(Double.isNaN(IplApertureSweep.crossing(before,after,2,0,0,0)),"endpoint-only sweep really misses this out-and-back arc");
        int slices=IplTransitSettlement.angularSlices(Math.toRadians(160),3),events=0,label=1;
        for(int i=1;i<=slices;i++) {
            Pose3d pose=new Pose3d();pose.orientation().set(start.orientation()).slerp(end.orientation(),(double)i/slices);
            var next=IplPieceMap.Aperture.of(p).toPlot(pose);
            double crossing=IplApertureSweep.crossing(before,next,2,0,0,0);
            if(!Double.isNaN(crossing)){events++;label=next.side(2,0,0)>=0?0:1;}
            before=next;
        }
        check(events==2&&label==1,"observed angular slices detect withdrawal AND reinsertion in one interval");
        check(IplTransitSettlement.angularSlices(0,1e9)==1,"huge linear motion is never time-sliced or speed-clamped");
        Random random=new Random(0x6c105e);
        for(int i=0;i<2000;i++) {
            Vector3d o=new Vector3d(random.nextDouble()*10-5,random.nextDouble()*10-5,random.nextDouble()*10-5);
            Quaterniond q=new Quaterniond().rotationXYZ(random.nextDouble()*6,random.nextDouble()*6,random.nextDouble()*6);
            var aperture=rect(o,q,0.1+random.nextDouble()*3,0.1+random.nextDouble()*3);
            double x=random.nextDouble()*8-4,y=random.nextDouble()*8-4,z=random.nextDouble()*8-4;
            Bounds bounds=new Bounds(x,y,z,x+0.2+random.nextDouble()*3,y+0.2+random.nextDouble()*3,z+0.2+random.nextDouble()*3);
            check(IplTransitSettlement.intersects(bounds,aperture)==clippedRectangleIntersects(bounds,aperture),"finite SAT agrees with polygon/box clipping");
            var interpolated=IplApertureSweep.interpolate(rect(new Vector3d(),new Quaterniond(),1,1),aperture,0.37);
            Vector3d w=new Vector3d(interpolated.wx(),interpolated.wy(),interpolated.wz()),h=new Vector3d(interpolated.hx(),interpolated.hy(),interpolated.hz());
            check(Math.abs(w.length()-1)<1e-10&&Math.abs(h.length()-1)<1e-10&&Math.abs(w.dot(h))<1e-10,"observed portal orientation remains orthonormal");
        }
    }

    /** Independent reference: clip the actual rectangle polygon by all six AABB halfspaces. */
    private static boolean clippedRectangleIntersects(Bounds b,IplPieceMap.Aperture a) {
        List<Vector3d> polygon=new ArrayList<>();
        for(int[] corner:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}})
            polygon.add(new Vector3d(a.ox()+corner[0]*a.halfW()*a.wx()+corner[1]*a.halfH()*a.hx(),
                a.oy()+corner[0]*a.halfW()*a.wy()+corner[1]*a.halfH()*a.hy(),
                a.oz()+corner[0]*a.halfW()*a.wz()+corner[1]*a.halfH()*a.hz()));
        double[] lo={b.minX(),b.minY(),b.minZ()},hi={b.maxX(),b.maxY(),b.maxZ()};
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
            List<Vector3d> next=new ArrayList<>();
            for(int i=0;i<polygon.size();i++) {
                Vector3d p=polygon.get(i),q=polygon.get((i+1)%polygon.size());
                double d=sign<0?p.get(axis)-lo[axis]:hi[axis]-p.get(axis);
                double e=sign<0?q.get(axis)-lo[axis]:hi[axis]-q.get(axis);
                if(d>=0)next.add(p);
                if((d>=0)!=(e>=0))next.add(new Vector3d(p).lerp(q,d/(d-e)));
            }
            polygon=next;if(polygon.isEmpty())return false;
        }
        return true;
    }

    private static void mappedGroupEnvelope() {
        reset();Portal p=portal(0,100);
        Quaterniond q=new Quaterniond().rotationXYZ(0.3,-0.5,0.2);p.rotation=new DQuaternion(q.x,q.y,q.z,q.w);
        var mapping=IplStraddlePoseMap.StraddleMapping.of(p);
        check(mapping.then(mapping.inverse()).sameAs(IplStraddlePoseMap.StraddleMapping.identity()),"real production mapping recognizes identity portal loops");
        ServerSubLevel a=ship(-5,2,0,1,2),b=ship(-1,3,0,1);
        a.logicalPose().orientation().rotationZ(0.4);b.logicalPose().orientation().rotationX(-0.7);
        var sa=new IplMaterialSnapshot(a.getPlot(),new IplPieceMap());
        var sb=new IplMaterialSnapshot(b.getPlot(),new IplPieceMap());
        Bounds nativeBounds=IplTransitSettlement.union(sa.boundsIn(a.logicalPose(),a.logicalPose()),sb.boundsIn(b.logicalPose(),a.logicalPose()));
        var mappedA=mapping.mapPose(a.logicalPose());var mappedB=mapping.mapPose(b.logicalPose());
        Bounds mapped=IplTransitSettlement.union(sa.boundsIn(mappedA,mappedA),sb.boundsIn(mappedB,mappedA));
        check(Math.abs(nativeBounds.minX()-mapped.minX())<1e-9&&Math.abs(nativeBounds.maxZ()-mapped.maxZ())<1e-9,
            "common portal isometry cancels from articulated group envelope");
        check(new IplMaterialSnapshot(a.getPlot(),new IplPieceMap()).total()==3,"implicit native sections count actual blocks, never air");
    }

    public static void main(String[] args) {
        stickAndWithdraw();gapsAreNotTimeouts();emptyJointConnector();siblingOffsetsAndEquivalentFrames();
        nativeVotesAndLoops();nestedHistoryAndReturn();failedOpenRollsBackWholeCommit();
        invalidatedSubtreeReconcilesAllStores();bodyReplacementAndPortalRetarget();reusedPieceHasNoOldFrameHistory();
        promotedFrameHasGeometryImmediately();
        groupOrderAndRepeatedCompletion();finiteGeometryAndRotation();mappedGroupEnvelope();
        System.out.println("PASS: "+checks+" session-lifecycle assertions");
    }
}
