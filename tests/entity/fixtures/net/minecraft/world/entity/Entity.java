package net.minecraft.world.entity;
import net.minecraft.world.phys.*;import net.minecraft.world.level.*;
public class Entity implements ipl.sable.mixin.IplEntityLevelInvoker,ipl.sable.mixin.IplEntityLifecycleAccessor {
 public Level level; public Vec3 pos=new Vec3(0,0,0);public AABB box=new AABB(0,0,0,1,1,1);
 public final java.util.UUID uuid=java.util.UUID.randomUUID();public boolean removed;
 public net.minecraft.world.level.entity.EntityInLevelCallback callback=r->{};
 public Entity(Level l){level=l;} public Level level(){return level;}public boolean isRemoved(){return removed;}
 public Vec3 position(){return pos;}public Object blockPosition(){return pos;}public AABB getBoundingBox(){return box;}
 public void setBoundingBox(AABB b){box=b;}public double getX(){return pos.x();}public double getZ(){return pos.z();}
 public float getYRot(){return 0;}public float getXRot(){return 0;}public java.util.UUID getUUID(){return uuid;}
 public void moveTo(double x,double y,double z,float ry,float rx){pos=new Vec3(x,y,z);box=new AABB(x,y,z,x+1,y+1,z+1);}
 public void ipl$invokeSetLevel(Level l){level=l;} public net.minecraft.world.level.entity.EntityInLevelCallback ipl$levelCallback(){return callback;}
 public enum RemovalReason {CHANGED_DIMENSION}
}

