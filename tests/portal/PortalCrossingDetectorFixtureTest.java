package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** Runs the actual production detector with explicitly bounded game API fixtures.
 * This is not a Minecraft/native/integration test; see README-PORTAL-FIX.md.
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
        System.out.println("0.5.2 finite-exit cases: passed");
        System.out.println("PASS: "+assertions+" detector fixture assertions");
    }
    private static void near(double a,double b,String s){check(Math.abs(a-b)<1e-7,s+": "+a+" / "+b);}
    private static void check(boolean ok,String s){assertions++;if(!ok)throw new AssertionError(s);}
}
