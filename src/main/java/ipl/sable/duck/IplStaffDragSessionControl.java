package ipl.sable.duck;

import org.joml.Quaterniondc;

/** Server-only control over Simulated's live staff constraint during a frame change. */
public interface IplStaffDragSessionControl {

    /** Body transited a rotated portal: reframe the held orientation target through it. */
    void ipl$reframeAfterTransit(Quaterniondc exitOrientation);

    /**
     * The dragging PLAYER teleported through a portal: rotate the stored player-relative
     * cursor vector so the physics substeps between the teleport and the client's next
     * (already new-frame) drag packet stay continuous.
     */
    void ipl$rotateRelativeGoal(Quaterniondc portalRotation);
}
