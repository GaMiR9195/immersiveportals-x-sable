package qouteall.imm_ptl.core.portal;
import java.util.*; import net.minecraft.server.level.ServerLevel; import net.minecraft.world.phys.Vec3; import qouteall.q_misc_util.my_util.DQuaternion;
public class Portal {
 public UUID id; public ServerLevel source,dest; public Vec3 origin,axisW,axisH,destination; public double width=4,height=4,scale=1; public boolean removed,teleportable=true;
 public DQuaternion rotation=new DQuaternion(0,0,0,1);
 public Portal(UUID id,ServerLevel source,ServerLevel dest,Vec3 origin,Vec3 destination){this.id=id;this.source=source;this.dest=dest;this.origin=origin;this.destination=destination;axisW=new Vec3(0,1,0);axisH=new Vec3(0,0,1);}
 public UUID getUUID(){return id;} public ServerLevel level(){return source;} public String getDestDim(){return dest.id;}
 public Vec3 getOriginPos(){return origin;} public Vec3 getDestPos(){return destination;} public Vec3 getAxisW(){return axisW;} public Vec3 getAxisH(){return axisH;}
 public double getWidth(){return width;} public double getHeight(){return height;} public double getScaling(){return scale;} public boolean isTeleportable(){return teleportable;}
 public DQuaternion getRotationD(){return rotation;} public boolean isRemoved(){return removed;}
 public Vec3 getNormal(){return new Vec3(axisW.y*axisH.z-axisW.z*axisH.y,axisW.z*axisH.x-axisW.x*axisH.z,axisW.x*axisH.y-axisW.y*axisH.x);}
}
