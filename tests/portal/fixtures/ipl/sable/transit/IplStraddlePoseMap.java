package ipl.sable.transit;
import dev.ryanhcode.sable.companion.math.*;import qouteall.imm_ptl.core.portal.Portal;import net.minecraft.world.phys.Vec3;
public class IplStraddlePoseMap {public static class StraddleMapping {
 private final Vec3 delta;private StraddleMapping(Portal p){delta=p.getDestPos().subtract(p.getOriginPos());}
 public static StraddleMapping of(Portal p){return new StraddleMapping(p);}public Vec3 mapPoint(Vec3 p){return p.add(delta);}public Vec3 mapVec(Vec3 v){return v;}
 public Pose3d mapPose(Pose3dc p){Pose3d r=new Pose3d(p);r.position().add(delta.x,delta.y,delta.z);return r;}
}}
