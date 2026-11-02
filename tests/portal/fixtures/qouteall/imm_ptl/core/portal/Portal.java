package qouteall.imm_ptl.core.portal;
import java.util.UUID;import net.minecraft.world.phys.Vec3;
public class Portal {
 private final UUID id=UUID.randomUUID();public Vec3 origin=Vec3.ZERO, destination=new Vec3(0,0,100);public double width=10,height=10;
 public UUID getUUID(){return id;}public Vec3 getOriginPos(){return origin;}public Vec3 getDestPos(){return destination;}
 public Vec3 normal=new Vec3(0,0,-1), axisW=new Vec3(1,0,0), axisH=new Vec3(0,1,0);
 public Vec3 getNormal(){return normal;}public Vec3 getAxisW(){return axisW;}public Vec3 getAxisH(){return axisH;}
 public net.minecraft.world.level.Level level(){return net.minecraft.world.level.Level.INSTANCE;}
 public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> getDestDim(){return net.minecraft.world.level.Level.FIXTURE;}
 public Vec3 getContentDirection(){return new Vec3(0,0,1);}public double getWidth(){return width;}public double getHeight(){return height;}
}
