package dev.ryanhcode.sable.companion.math;
import org.joml.*;
public class Pose3d implements Pose3dc {
 private final Vector3d p=new Vector3d(), r=new Vector3d(); private final Quaterniond q=new Quaterniond();
 public Pose3d(){} public Pose3d(Pose3dc a){p.set(a.position());r.set(a.rotationPoint());q.set(a.orientation());}
 public Vector3d position(){return p;} public Vector3d rotationPoint(){return r;} public Quaterniond orientation(){return q;}
}
