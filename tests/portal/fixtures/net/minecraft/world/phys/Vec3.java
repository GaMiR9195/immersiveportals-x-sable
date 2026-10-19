package net.minecraft.world.phys;
public class Vec3 {
 public final double x,y,z; public static final Vec3 ZERO=new Vec3(0,0,0);
 public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public Vec3 subtract(Vec3 o){return new Vec3(x-o.x,y-o.y,z-o.z);}
 public Vec3 add(Vec3 o){return new Vec3(x+o.x,y+o.y,z+o.z);}
 public Vec3 scale(double s){return new Vec3(x*s,y*s,z*s);}
 public double dot(Vec3 o){return x*o.x+y*o.y+z*o.z;}
 public double length(){return Math.sqrt(dot(this));}
 public double distanceToSqr(Vec3 o){Vec3 d=subtract(o);return d.dot(d);}
}
