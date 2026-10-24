package dev.ryanhcode.sable.physics.impl.rapier;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
/** Exact JNI declarations; no Minecraft bootstrap or alternate physics implementation. */
public class Rapier3D {
 public static native long initialize(double x,double y,double z,double drag);
 public static native int newVoxelCollider(double friction,double volume,double restitution,boolean fluid,BlockSubLevelCollisionCallback callback);
 public static native void addVoxelColliderBox(int index,double[] bounds);
 public static native long createRope(long scene,double radius,double length,double[] points,int count);
 public static native long removeRope(long scene,long id);
 public static native double[] queryRope(long scene,long id);
 public static native void dispose(long scene);
}
