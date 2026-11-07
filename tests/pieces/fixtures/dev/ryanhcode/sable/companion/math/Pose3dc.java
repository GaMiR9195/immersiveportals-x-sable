package dev.ryanhcode.sable.companion.math;

import org.joml.Vector3d;

/** The two inverse transforms {@code IplPieceMap.Aperture#toPlot} uses (in place, like Sable). */
public interface Pose3dc {
    Vector3d transformPositionInverse(Vector3d v);

    Vector3d transformNormalInverse(Vector3d v);
}
