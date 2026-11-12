package ipl.sable.duck;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
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

    /** The body object this session drives (Simulated keeps it in a final field). */
    ServerSubLevel ipl$body();

    /**
     * Cross-dimension move: the body continues as {@code successor} (same UUID, destination
     * level). The session keeps its player, cursor vector, orientation and grab chain; only
     * the body object and, when the plot slot changed, the plot-local anchor move.
     */
    void ipl$rebindBody(ServerSubLevel successor, Vector3dc plotShift);

    /**
     * A drag packet composed before the client re-bound its own session to the successor
     * still carries the old plot-local anchor. Exactly that anchor is shifted with the plot;
     * any other value passes through and ends the compensation.
     */
    Vector3dc ipl$mapIncomingAnchor(Vector3dc anchor);
}
