package net.minecraft.world.phys;
public class Vec3 {
 public static final Vec3 ZERO=new Vec3(0,0,0); public final double x,y,z;
 public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public Vec3 add(Vec3 b){return new Vec3(x+b.x,y+b.y,z+b.z);}
 public Vec3 subtract(Vec3 b){return new Vec3(x-b.x,y-b.y,z-b.z);}
 public Vec3 lerp(Vec3 b,double t){return new Vec3(x+(b.x-x)*t,y+(b.y-y)*t,z+(b.z-z)*t);}
 public double distanceToSqr(Vec3 b){return (x-b.x)*(x-b.x)+(y-b.y)*(y-b.y)+(z-b.z)*(z-b.z);}
 @Override public boolean equals(Object o){return o instanceof Vec3 b && x==b.x && y==b.y && z==b.z;}
 @Override public int hashCode(){return java.util.Objects.hash(x,y,z);}
}
