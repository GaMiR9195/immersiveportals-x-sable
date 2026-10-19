package dev.ryanhcode.sable.companion.math;
import org.joml.*;
public class Pose3d implements Pose3dc {
 private final Vector3d p=new Vector3d(), pivot=new Vector3d(), scale=new Vector3d(1); private final Quaterniond q=new Quaterniond();
 public Pose3d(){} public Pose3d(Pose3dc o){set(o);} public Pose3d set(Pose3dc o){p.set(o.position());pivot.set(o.rotationPoint());scale.set(o.scale());q.set(o.orientation());return this;}
 public Vector3d position(){return p;} public Vector3d rotationPoint(){return pivot;} public Vector3d scale(){return scale;} public Quaterniond orientation(){return q;}
}
