package dev.ryanhcode.sable.api.physics.callback;
public interface BlockSubLevelCollisionCallback {
 double[] onCollision(int x,int y,int z,int a,int b,int c,double px,double py,double pz,double impact,boolean other);
}
