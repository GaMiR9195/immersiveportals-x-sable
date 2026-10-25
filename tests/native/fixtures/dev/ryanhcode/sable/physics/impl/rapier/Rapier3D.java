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
 public static native void step(long scene,double dt);
 public static native void createSubLevel(long scene,int id,double[] pose);
 public static native void setCenterOfMass(long scene,int id,double x,double y,double z);
 public static native void setLocalBounds(long scene,int id,int ax,int ay,int az,int bx,int by,int bz);
 public static native void setMassProperties(long scene,int id,double mass,double[] com,double[] inertia);
 public static native void addChunk(long scene,int x,int y,int z,int[] blocks,boolean global,int id);
 public static native void getPose(long scene,int id,double[] pose);
 public static native void dispose(long scene);
}
