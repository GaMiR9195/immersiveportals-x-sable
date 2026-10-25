package net.minecraft.world.phys;
public final class Vec3 {
 public final double x,y,z;public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double x(){return x;}public double y(){return y;}public double z(){return z;}
 public Vec3 subtract(Vec3 p){return new Vec3(x-p.x,y-p.y,z-p.z);}
 public boolean equals(Object v){return v instanceof Vec3 p&&x==p.x&&y==p.y&&z==p.z;}
 public int hashCode(){return java.util.Objects.hash(x,y,z);}
}
