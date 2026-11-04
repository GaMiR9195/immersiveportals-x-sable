package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplStraddleRenderState;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

import java.util.List;

/**
 * For a given client-side sub-level, the clip cell of the draw being made — so
 * {@link ChunkedRenderDataMixin} and the block-entity brackets can install the planes that
 * keep exactly one piece of a straddling body, and so client collision/interaction can tell
 * whether it straddles at all.
 *
 * <p><b>Server-authoritative:</b> which part of a straddling ship is "still here" is history,
 * not geometry (a ship 60% through is geometrically identical to one 40% through the other
 * way), so nothing is guessed from geometry. The sessions come from
 * {@code IplStraddleSessionStore} — the client mirror of the transit controller's latch,
 * with each session's passage anchor — and only the region GEOMETRY is derived per frame
 * from the portals' current transforms ({@link IplClientProjections.RenderPlan}).
 */
public final class IplClipPortalFinder {

    private IplClipPortalFinder() {}

    /**
     * The portal the piece belongs to and its convex clip cell: the kept region is the
     * intersection of {@code normal · (p - pos) >= 0} over {@code planes} (empty = nothing to
     * cut). For the native piece {@code portal} is its first root portal.
     */
    public record ClipDecision(Portal portal, List<Plane> planes) {}

    /**
     * The cell of the current draw of {@code sub}: the armed projection's cell while one is
     * armed ({@link IplStraddleRenderState}), otherwise the first cell of the native piece; null
     * when the body straddles nothing.
     */
    @Nullable
    public static ClipDecision findStraddlingPortalPlane(ClientSubLevel sub) {
        if (sub == null) return null;
        // A projection pass installs its own piece's cell instead of the native one. Its planes
        // are not widened: the native cells and the projections' cells tile the body exactly, and
        // shifting a cut by FrontClipping.ADJUSTMENT (IP's z-fighting guard for its destination
        // TERRAIN pass) would draw a 1 cm slab twice along everything poking through.
        List<Plane> armed = IplStraddleRenderState.getPlanesFor(sub);
        if (armed != null) return new ClipDecision(IplStraddleRenderState.getPortalFor(sub), armed);
        return IplClientProjections.renderPlan(sub).nativeDecision();
    }
}
