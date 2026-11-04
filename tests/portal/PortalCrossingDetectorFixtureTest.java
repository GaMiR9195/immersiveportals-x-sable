package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Runs the production detector against explicitly bounded game API fixtures.
 * Not a Minecraft, native or integration test.
 */
public class PortalCrossingDetectorFixtureTest {
    private static final Vec3 FORWARD=new Vec3(0,0,1);
    private static int assertions;
    public static void main(String[] args) {
        PortalCrossingDetector.clearTrails();
        Portal portal=new Portal();ServerSubLevel body=new ServerSubLevel();
        body.current.position().set(0,0,-5);
        PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().set(0,0,20);
        // Reproduce hosting observer overwriting stock lastPose AFTER fused physics.
        body.previous.set(body.current);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        var state=PortalCrossingDetector.evaluate(body,portal,FORWARD,0);
        check(state.phase()==PortalCrossingDetector.CrossingPhase.CROSSED,"fully crossed");
        check(state.sweptTowardDestination(),"pre-fused A survives lastPose overwrite");
        near(state.entryTime(),.18,"first cube-plane contact");
        double completion=PortalCrossingDetector.completionTime(body,portal);
        near(completion,.224,"whole body + 0.1 clears, not leading contact");
        Pose3d mapped=IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(body.current);
        body.current.set(mapped);
        PortalCrossingDetector.rebaseTrailThroughPortal(body,portal,completion);
        var anchor=PortalCrossingDetector.bufferedExitAnchor(body);
        near(anchor.position().z(),100.6,"anchor is actual mapped completion pose");
        near(body.current.position().z(),120,"B maps exactly");
        Portal second=new Portal();second.origin=new Vec3(0,0,105);second.destination=new Vec3(0,0,50);
        var next=PortalCrossingDetector.evaluate(body,second,FORWARD,0);
        check(next.sweptTowardDestination(),"next doorway detected in SAME residual segment");
        check(next.phase()==PortalCrossingDetector.CrossingPhase.CROSSED,"second doorway completion");
        check(PortalCrossingDetector.settleExit(body).equals(portal.getUUID()),"exit settles immediately when clear");
        check(PortalCrossingDetector.bufferedExitAnchor(body)!=null,"settlement preserves residual anchor");
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(PortalCrossingDetector.hasBufferedExit(body),"consumed anchor remains identified on trail");
        check(PortalCrossingDetector.bufferedExitAnchor(body)==null,"map anchor consumed once");

        // Exit contact cannot vanish merely because three ticks elapsed.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();body.current.position().set(0,0,100);
        PortalCrossingDetector.seedPhysicsStart(body);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        PortalCrossingDetector.armExitClearance(body,portal);
        Portal exit=new Portal();exit.origin=new Vec3(0,0,100);
        for(int i=0;i<12;i++) {
            PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
            check(PortalCrossingDetector.settleExit(body)==null,"contact survives tick "+i);
            check(PortalCrossingDetector.touchesPortalVirtually(body,exit),"same contact predicate "+i);
        }
        body.current.position().set(0,0,102);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(PortalCrossingDetector.settleExit(body).equals(portal.getUUID()),"release by geometry");
        check(!PortalCrossingDetector.touchesPortalVirtually(body,exit),"no stale latch after release");

        // A touched plane does NOT retain a laterally departed session.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();body.current.position().set(0,0,-2);
        PortalCrossingDetector.seedPhysicsStart(body);body.current.position().set(20,0,0);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        state=PortalCrossingDetector.evaluate(body,portal,FORWARD,0);
        check(!state.currentIntersectsPortalAperture(),"finite lateral retention");
        check(!state.sweptIntersectsPortalAperture(),"oblique path misses doorway");

        // Rotational broad phase must include an arm's middle arc, not only endpoint boxes.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();body.blocks.clear();
        body.blocks.add(new net.minecraft.core.BlockPos(10,0,0));
        body.current.orientation().rotationY(-Math.PI/4);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.orientation().rotationY(Math.PI/4);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        var swept=PortalCrossingDetector.sweptBounds(body);
        check(swept.maxX>=10.5,"rotational broad phase contains middle arc");
        PortalCrossingDetector.pruneTrails();
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.pruneTrails();
        check(PortalCrossingDetector.bufferedPose(body)==null,"removed bodies have no retained trail");
        // Interior rotational arc can touch a small aperture missed by both endpoints.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();body.blocks.clear();
        body.blocks.add(new net.minecraft.core.BlockPos(10,0,0));
        body.current.orientation().rotationY(Math.PI/4);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.orientation().rotationY(-Math.PI/4);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        Portal narrow=new Portal();narrow.origin=new Vec3(10,0,0);narrow.width=.5;narrow.height=2;
        state=PortalCrossingDetector.evaluate(body,narrow,FORWARD,0);
        check(state.sweptIntersectsPortalAperture(),"rotation hits between endpoints");
        check(state.sweptTowardDestination(),"rotating occupied block owns crossing direction");
        check(!state.currentIntersectsPortalAperture(),"rotating final endpoint is outside finite doorway");

        // A far plot slot must not change entry plane or crossing time.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();body.blocks.clear();
        body.blocks.add(new net.minecraft.core.BlockPos(10000000,0,0));
        body.current.rotationPoint().set(10000000.5,.5,.5);
        body.current.orientation().rotationY(Math.PI/4);body.current.position().set(0,0,-5);
        PortalCrossingDetector.seedPhysicsStart(body);body.current.position().set(0,0,5);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        state=PortalCrossingDetector.evaluate(body,portal,FORWARD,0);
        near(state.entryTime(),(5-Math.sqrt(.5))/10,"far-slot rotated support");

        // A sparse construction's enclosing AABB is not physical exit contact.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();body.blocks.clear();
        body.blocks.add(new net.minecraft.core.BlockPos(-10,0,0));
        body.blocks.add(new net.minecraft.core.BlockPos(10,0,0));
        body.current.position().set(0,0,100);PortalCrossingDetector.seedPhysicsStart(body);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        Portal tiny=new Portal();tiny.width=2;tiny.height=2;
        PortalCrossingDetector.armExitClearance(body,tiny);
        check(PortalCrossingDetector.settleExit(body).equals(tiny.getUUID()),"empty AABB space cannot hold exit");
        // 0.5.2: final contact must be finite, independently of phase hysteresis.
        PortalCrossingDetector.clearTrails(); body=new ServerSubLevel();
        Portal door=new Portal();door.width=2;door.height=2;
        body.current.position().set(0,0,-2);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().set(0,0,2);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(PortalCrossingDetector.completesThroughAperture(body,door),"finite trailing-edge exit");

        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();
        body.current.position().set(0,0,-.2);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().set(0,0,.55);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(PortalCrossingDetector.completesThroughAperture(body,door),"zero-plane exit before phase hysteresis");
        check(!PortalCrossingDetector.hasSourceGeometry(body,door),"whole body past mathematical plane");
        check(PortalCrossingDetector.evaluate(body,door,FORWARD,0).phase()
            ==PortalCrossingDetector.CrossingPhase.STRADDLING,"phase still within 0.1 band");

        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();
        body.current.position().set(0,0,-.2);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().set(10,0,2);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(!PortalCrossingDetector.completesThroughAperture(body,door),"sideways withdrawal cannot finish through infinite plane");
        // A body resting across the frame edge overlaps the opening but is not admitted.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();
        body.current.position().set(.55,0,0); // far edge is 0.05 beyond a width=2 door
        PortalCrossingDetector.seedPhysicsStart(body);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        state=PortalCrossingDetector.evaluate(body,door,FORWARD,0);
        check(state.currentIntersectsPortalAperture(),"section still overlaps the true opening");
        check(!state.sweptTowardDestination(),"retention skin cannot admit stationary body");
        check(!PortalCrossingDetector.willEnterAperture(body,door,FORWARD,0,1),"stationary prediction fast path");

        // Without a session (one-tick passage) every block centre must cross inside the aperture.
        body=moving(0,0,-2,0,0,2);
        check(PortalCrossingDetector.crossedWithinAperture(body,door),"one-tick passage through the frame");
        check(PortalCrossingDetector.flipReadyThrough(body,door),"sessionless flip is judged per block");
        near(PortalCrossingDetector.entryAnchor(body,door).z,4,"entry anchor follows the segment");
        body=moving(3,0,-2,3,0,2);
        check(!PortalCrossingDetector.flipReadyThrough(body,door),"passing beside the frame never flips");
        body=moving(0,0,.5,0,0,2);
        check(!PortalCrossingDetector.flipReadyThrough(body,door),"material already beyond the plane needs a session");

        // With a session only the prism counts: a block beyond the plane BESIDE the frame stays
        // native, keeps the body stitched and blocks the flip (the rim collision handles it).
        body=resting(0,0,1);body.blocks.add(new net.minecraft.core.BlockPos(3,0,0));
        IplPortalVolumeCache.invalidate(body);
        IplProjectionTree.install(body,door);
        String doorPath=door.getUUID().toString();
        check(PortalCrossingDetector.pieceHoldsMaterial(body,doorPath),"block in the prism holds the session");
        check(!PortalCrossingDetector.flipReadyThrough(body,door),"block beside the frame stays native");
        body.blocks.remove(1);IplPortalVolumeCache.invalidate(body);
        check(PortalCrossingDetector.flipReadyThrough(body,door),"whole body in the prism flips");
        body.current.position().set(0,0,-.7);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(!PortalCrossingDetector.pieceHoldsMaterial(body,doorPath),"withdrawal empties the piece");

        // Branches: pushed into two portals at once, the root holding more blocks takes the body
        // (ties: sibling priority) and carries the rest along.
        Portal other=new Portal();other.origin=new Vec3(10,0,0);other.width=2;other.height=2;
        body=resting(0,0,1);body.blocks.add(new net.minecraft.core.BlockPos(0,1,0));
        body.blocks.add(new net.minecraft.core.BlockPos(10,0,0));
        IplPortalVolumeCache.invalidate(body);
        IplProjectionTree.install(body,other,door);
        check(PortalCrossingDetector.pieceHoldsMaterial(body,other.getUUID().toString()),"second branch holds material");
        check(PortalCrossingDetector.flipReadyThrough(body,door),"majority branch takes the body");
        check(!PortalCrossingDetector.flipReadyThrough(body,other),"minority branch yields");
        body.blocks.remove(1);IplPortalVolumeCache.invalidate(body);
        check(PortalCrossingDetector.flipReadyThrough(body,other),"tie goes to sibling priority");
        check(!PortalCrossingDetector.flipReadyThrough(body,door),"tie: exactly one branch flips");

        // Tolerant trailing exit is explicit and session-owned, never the default.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();
        body.current.position().set(.55,0,-.2);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().z=2;
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(!PortalCrossingDetector.completesThroughAperture(body,door),"strict exit rejects off-aperture path");
        check(PortalCrossingDetector.completesThroughAperture(body,door,PortalCrossingDetector.SESSION_RELEASE_MARGIN),
            "admitted exit tolerates bounded solver displacement");
        body.current.position().set(2,0,-.2);PortalCrossingDetector.resetTrail(body);
        body.current.position().z=2;
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(!PortalCrossingDetector.completesThroughAperture(body,door,PortalCrossingDetector.SESSION_RELEASE_MARGIN),
            "retention margin is not an infinite completion plane");

        // A fast exact entry can be admitted and finish inside one server tick:
        // no prior session is required, but there MUST be an actual strict sweep hit.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();
        body.current.position().set(0,0,-2);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().set(.65,0,.65);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        state=PortalCrossingDetector.evaluate(body,door,FORWARD,0);
        check(state.provesPhysicalEntry(),"same-tick entry has strict finite proof");
        check(!PortalCrossingDetector.completesThroughAperture(body,door),"slightly displaced trailing exit misses exact rectangle");
        check(PortalCrossingDetector.completesThroughAperture(body,door,PortalCrossingDetector.SESSION_RELEASE_MARGIN),
            "same-tick proven admission can use release skin");

        // Empty space between occupied blocks is not material of a session either.
        body=new ServerSubLevel();body.blocks.clear();
        body.blocks.add(new net.minecraft.core.BlockPos(-10,0,0));
        body.blocks.add(new net.minecraft.core.BlockPos(10,0,0));
        PortalCrossingDetector.seedPhysicsStart(body);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        IplProjectionTree.install(body,door);
        check(!PortalCrossingDetector.pieceHoldsMaterial(body,door.getUUID().toString()),"sparse AABB gap holds no material");

        // A state cache must be reused within a segment, not across edits/frames.
        PortalCrossingDetector.clearTrails();body=new ServerSubLevel();
        body.current.position().set(0,0,-2);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().z=2;
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        var first=PortalCrossingDetector.evaluate(body,door,FORWARD,0);
        check(first==PortalCrossingDetector.evaluate(body,door,FORWARD,0),"same segment uses cached state");
        double oldWidth=door.width;door.width=.25;
        var resized=PortalCrossingDetector.evaluate(body,door,FORWARD,0);
        check(first!=resized,"aperture resize invalidates cache key");
        door.width=oldWidth;door.origin=new Vec3(20,0,0);
        check(!PortalCrossingDetector.evaluate(body,door,FORWARD,0).sweptIntersectsPortalAperture(),"portal movement invalidates cache");
        door.origin=Vec3.ZERO;
        body.blocks.clear();body.blocks.add(new net.minecraft.core.BlockPos(20,0,0));
        IplPortalVolumeCache.invalidate(body);
        check(!PortalCrossingDetector.evaluate(body,door,FORWARD,0).sweptIntersectsPortalAperture(),"block revision invalidates cache");
        body=new ServerSubLevel();PortalCrossingDetector.seedPhysicsStart(body);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        first=PortalCrossingDetector.evaluate(body,door,FORWARD,0);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        check(first!=PortalCrossingDetector.evaluate(body,door,FORWARD,0),"new captured segment invalidates cache");
        body.current.position().set(0,0,.55);PortalCrossingDetector.resetTrail(body);
        state=PortalCrossingDetector.evaluate(body,door,FORWARD,0);
        check(state.currentIntersectsPortalAperture(),"normal hysteresis contact remains");
        check(!state.sweptIntersectsPortalAperture(),"stationary sweep still has zero thickness");
        System.out.println("finite-exit, prism-piece, branch and cache cases: passed");
        System.out.println("PASS: "+assertions+" detector fixture assertions");
    }
    /** Single-block body whose last trail segment runs from (x0,y0,z0) to (x1,y1,z1). */
    private static ServerSubLevel moving(double x0,double y0,double z0,double x1,double y1,double z1) {
        PortalCrossingDetector.clearTrails();ServerSubLevel body=new ServerSubLevel();
        body.current.position().set(x0,y0,z0);PortalCrossingDetector.seedPhysicsStart(body);
        body.current.position().set(x1,y1,z1);
        PortalCrossingDetector.beginTrailTick();PortalCrossingDetector.captureTrail(body);
        return body;
    }
    private static ServerSubLevel resting(double x,double y,double z) { return moving(x,y,z,x,y,z); }
    private static void near(double a,double b,String s){check(Math.abs(a-b)<1e-7,s+": "+a+" / "+b);}
    private static void check(boolean ok,String s){assertions++;if(!ok)throw new AssertionError(s);}
}
