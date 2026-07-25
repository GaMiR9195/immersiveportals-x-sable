package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

/**
 * For a given client-side sub-level, the portal whose plane it currently straddles —
 * so {@link SableSourceClipMixin} can install a clip plane that culls the geometry on
 * the dest side, and so client collision/interaction can select frames consistently.
 *
 * <p><b>Server-authoritative since the session sync:</b> which half of a straddling
 * ship is "still here" is historical state (a ship 60% through is geometrically
 * identical to one 40% through the other way), so this class no longer guesses it from
 * geometry. The straddle portal comes from {@code IplStraddleSessionStore} — the client
 * mirror of the transit controller's latch — and only the plane GEOMETRY is derived
 * per frame from that portal entity's current transform (moving portals stay smooth;
 * parity switches exactly when the server's latch does). The former heuristics
 * (orient-toward-center, contact-normal locking, crossing latches with expiry
 * deadlines) are gone with their failure modes: parked-ship false clips, halfway
 * parity flips on bi-faced portals, stale locks across same-dimension handoffs.
 */
public final class SourceClipPortalFinder {

    private SourceClipPortalFinder() {}

    /**
     * The chosen portal and its source-side clip plane. Convention: the kept
     * half-space is {@code normal · (p - pos) > 0}; the plane normal points to the
     * source side of the portal, where the ship's native frame remains.
     */
    public record ClipDecision(Portal portal, Plane plane) {}

    @Nullable
    public static ClipDecision findStraddlingPortalPlane(ClientSubLevel sub) {
        if (sub == null) return null;

        // Dest-side projection pass (dim-agnostic straddle rendering): the projection
        // driver has already computed the complementary plane — install that instead of
        // searching. This makes the legacy bracket the single clip installer for both
        // sides of a partial crossing.
        qouteall.q_misc_util.my_util.Plane projectionPlane =
            ipl.sable.client.IplStraddleRenderState.getPlaneFor(sub);
        if (projectionPlane != null) {
            // IP keeps its destination terrain clip at -ADJUSTMENT to avoid z-fighting
            // around portal planes. The mapped sub-level half previously kept its cut on
            // the mathematical plane, leaving a moving-only empty hairline between CD0 and
            // this CD1 split. Preserve IP's offset and move only the projection's kept
            // half by the identical amount, making the two clips meet with no exposed gap.
            Plane seamMatchedProjectionPlane = isUsingMatchingPortalClip(projectionPlane)
                ? projectionPlane.move(-FrontClipping.ADJUSTMENT) : projectionPlane;
            return new ClipDecision(
                ipl.sable.client.IplStraddleRenderState.getPortalFor(sub), seamMatchedProjectionPlane);
        }

        if (ipl.sable.client.IplStraddleRenderCache.hasDecision(sub)) {
            return ipl.sable.client.IplStraddleRenderCache.decision(sub);
        }
        ClipDecision decision = ipl$findAuthoritative(sub);
        ipl.sable.client.IplStraddleRenderCache.cacheDecision(sub, decision);
        return decision;
    }

    /**
     * The overlap belongs only to IP's offset portal terrain pass. A same-dimension image
     * drawn in the ordinary main pass has no slot-0 offset and must retain its exact
     * half-open split, otherwise its source and mapped instances would z-fight.
     */
    private static boolean isUsingMatchingPortalClip(Plane projectionPlane) {
        if (!PortalRendering.isRendering() || !FrontClipping.isClippingEnabled) return false;
        Plane active = PortalRendering.getActiveClippingPlane();
        return active != null
            && active.normal().dot(projectionPlane.normal()) > 0.999999
            && active.pos().distanceToSqr(projectionPlane.pos()) < 1.0e-8;
    }

    /**
     * The session store's portal, with the plane rebuilt from its live transform. The
     * session is keyed on the canonical ENTRANCE face, whose normal by IP convention
     * points at the remaining (source) half — exactly the kept side, no orientation
     * heuristic needed.
     */
    @Nullable
    private static ClipDecision ipl$findAuthoritative(ClientSubLevel sub) {
        Portal portal = ipl.sable.client.IplStraddleSessionStore.resolveRenderPortal(sub);
        if (portal == null) return null;

        Vec3 origin = portal.getOriginPos();
        Vec3 keepNormal = portal.getNormal();
        return new ClipDecision(portal, new Plane(origin, keepNormal));
    }

    /**
     * ALL current straddle decisions for this sub (multi-straddle), session-start
     * order. The source render's kept region is the INTERSECTION of every decision's
     * half-space; the shader takes {@code min} over two cut planes, so brackets feed
     * the first two (more than two simultaneous straddles logs and clips the rest as
     * best-effort with the first pair).
     */
    public static java.util.List<ClipDecision> findStraddlingPortalPlanes(ClientSubLevel sub) {
        if (sub == null) return java.util.List.of();
        java.util.List<Portal> portals =
            ipl.sable.client.IplStraddleSessionStore.resolveAllRenderPortals(sub);
        if (portals.isEmpty()) return java.util.List.of();
        java.util.List<ClipDecision> out = new java.util.ArrayList<>(portals.size());
        for (Portal portal : portals) {
            out.add(new ClipDecision(portal, new Plane(portal.getOriginPos(), portal.getNormal())));
        }
        return out;
    }
}
