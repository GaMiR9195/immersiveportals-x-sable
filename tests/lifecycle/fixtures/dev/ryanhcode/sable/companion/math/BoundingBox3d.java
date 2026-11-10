package dev.ryanhcode.sable.companion.math;
public class BoundingBox3d implements BoundingBox3dc {
 private double x,y,z,a,b,c;
 public double minX(){return x;} public double minY(){return y;} public double minZ(){return z;}
 public double maxX(){return a;} public double maxY(){return b;} public double maxZ(){return c;}
 public void set(double x,double y,double z,double a,double b,double c){this.x=x;this.y=y;this.z=z;this.a=a;this.b=b;this.c=c;}
 public void expandTo(double a,double b,double c){set(Math.min(x,a),Math.min(y,b),Math.min(z,c),Math.max(this.a,a),Math.max(this.b,b),Math.max(this.c,c));}
 public boolean intersects(BoundingBox3dc o){return a>=o.minX()&&o.maxX()>=x&&b>=o.minY()&&o.maxY()>=y&&c>=o.minZ()&&o.maxZ()>=z;}
}
