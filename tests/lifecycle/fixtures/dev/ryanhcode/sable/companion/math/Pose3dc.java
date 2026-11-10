package dev.ryanhcode.sable.companion.math;
import org.joml.*;
public interface Pose3dc {
 Vector3d position(); Quaterniond orientation(); Vector3d rotationPoint();
 default Vector3d transformPosition(Vector3d p){return orientation().transform(p.sub(rotationPoint())).add(position());}
 default Vector3d transformPositionInverse(Vector3d p){return orientation().transformInverse(p.sub(position())).add(rotationPoint());}
 default Vector3d transformNormalInverse(Vector3d p){return orientation().transformInverse(p);}
}
