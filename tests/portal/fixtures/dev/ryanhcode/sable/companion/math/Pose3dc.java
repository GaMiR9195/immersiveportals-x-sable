package dev.ryanhcode.sable.companion.math;
import org.joml.*; import net.minecraft.world.phys.Vec3;
public interface Pose3dc {
 Vector3dc position(); Quaterniondc orientation(); Vector3dc rotationPoint(); Vector3dc scale();
 // Same transform order as the supplied companion Pose3dc. Serialization is irrelevant here.
 default Vec3 transformPosition(Vec3 p){Vector3d v=new Vector3d(p.x,p.y,p.z).sub(rotationPoint()).mul(scale());orientation().transform(v);v.add(position());return new Vec3(v.x,v.y,v.z);}
}
