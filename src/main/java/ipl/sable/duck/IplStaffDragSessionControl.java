package ipl.sable.duck;

import org.joml.Quaterniondc;

/** Server-only control over Simulated's live staff constraint during a parent-frame flip. */
public interface IplStaffDragSessionControl {
    void ipl$reframeAfterTransit(Quaterniondc exitOrientation);
}
