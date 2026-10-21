package ipl.sable.duck;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** Server-only control over Simulated's live staff constraint during a frame change. */
public interface IplStaffDragSessionControl {

    /** Body transited: move the live native motor target and basis into portal destination frame. */
    void ipl$reframeAfterTransit(Portal portal);

    /**
     * The dragging PLAYER teleported through a portal: map the stored player-relative
     * cursor vector into the destination frame so the physics substeps between the
     * teleport and the client's next (already new-frame) drag packet stay continuous.
     */
    void ipl$reframePlayerGoal(Portal portal, Vec3 previousEyePosition);

    /** Remove the native staff joint before its session leaves Simulated's map. */
    void ipl$removeConstraint();
}
