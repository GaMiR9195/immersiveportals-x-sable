package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.BoxPredicateF;
import qouteall.q_misc_util.my_util.Plane;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Finds, for a given client-side sub-level being rendered, the portal whose plane
 * it currently straddles -- so {@link SableSourceClipMixin} can install a clip plane
 * that culls the geometry that's on the dest-dim side of the portal.
 *
 * <p>Why: Phase 2's mirror lifecycle puts a "phantom" copy of the airship in the dest
 * dim near the portal. While the source airship is mid-crossing, it visibly sticks out
 * past the portal frame in source-dim coordinates. Through the portal we render the
 * dest-dim view, which (via IP's portal-through rendering) shows the mirror on the
 * other side. To make the airship look like a single coherent body straddling the
 * frame -- rather than a full airship that's clipping through a window -- the source
 * sub-level needs to be clipped at the portal plane.
 *
 * <p>"Straddling" here means the sub-level's world-space AABB has corners on both
 * sides of the portal's plane. Cheap and conservative (false positives are fine --
 * they just enable a clip that has nothing to cull).
 *
 * <p>If multiple portals straddle the same sub-level (unusual but possible in
 * portal-dense setups), we pick the one whose origin is closest to the sub-level's
 * AABB center. That's the same heuristic the server-side controller uses for mirror
 * spawning, so the visual and the spawn logic align.
 */
public final class SourceClipPortalFinder {

    /**
     * Last valid source/destination split for an in-flight hosted ship. A local client can
     * observe the ship fully past the plane before it receives the server's parent-flip RPC.
     * Keeping this decision through that short window prevents the destination projection from
     * being withdrawn a frame before normal destination rendering takes over.
     */
    private static final ConcurrentMap<UUID, ClipDecision> CROSSING_LATCHES =
        new ConcurrentHashMap<>();

    /**
     * Lateral tolerance for the "is the airship in the portal opening" gate,
     * as a fraction of the portal's half-dimension. The airship's AABB center
     * must project within {@code halfDim * (1 + margin)} on both the width and
     * height axes for clipping to engage. Tunable at runtime via
     * {@code -Dipl.sable.clip.rectMargin} so the gate can be dialed without a
     * rebuild. Default 0.15 (center must be within ~the opening, plus 15% slack
     * at the edges to avoid flicker).
     */
    private static final double RECT_MARGIN_FACTOR =
        Double.parseDouble(System.getProperty("ipl.sable.clip.rectMargin", "0.15"));

    private SourceClipPortalFinder() {}

    /**
     * Result struct: the portal and a pre-built {@link Plane} ready to feed to IP's
     * {@code FrontClipping.setupInnerClipping}. The plane's normal points to the
     * <i>source</i> side -- i.e., the side we want kept. IP's clip equation form is
     * {@code normal · p + c > 0}, so points where the dot product is positive are
     * retained; that's the source side of the portal, which is where the camera is.
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
            return new ClipDecision(
                ipl.sable.client.IplStraddleRenderState.getPortalFor(sub), projectionPlane);
        }

        if (ipl.sable.client.IplStraddleRenderCache.hasDecision(sub)) {
            return ipl.sable.client.IplStraddleRenderCache.decision(sub);
        }
        ClipDecision decision = ipl$findUncached(sub);
        ipl.sable.client.IplStraddleRenderCache.cacheDecision(sub, decision);
        return decision;
    }

    @Nullable
    private static ClipDecision ipl$findUncached(ClientSubLevel sub) {

        // Hosted sub-levels live in ipl_sable:sublevels, which contains no portals — the
        // straddle search must run in the PARENT dimension (where the ship visually is).
        // Falls back to getLevel() for legacy embedded sub-levels.
        if (!(ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) instanceof ClientLevel level)) return null;

        AABB box = sub.boundingBox().toMojang();
        Vec3 center = box.getCenter();

        Portal best = null;
        double bestDist = Double.MAX_VALUE;
        Plane bestPlane = null;
        boolean anyStraddle = false;

        // Candidates: portal entities PLUS global portals (dimension-stack seams live in
        // GlobalPortalStorage and never appear in entity iteration). Entity iteration is
        // cheap enough for the small entity counts typical in vanilla survival; if this
        // shows up in profilers we can swap to a chunk-section query around the AABB.
        java.util.List<Portal> candidates =
            ipl.sable.client.IplStraddleRenderCache.portalCandidates(level);
        if (candidates == null) {
            candidates = collectCandidates(level);
            ipl.sable.client.IplStraddleRenderCache.cachePortalCandidates(level, candidates);
        }

        for (Portal portal : candidates) {
            if (!portal.isTeleportable()) continue;
            if (!isPortalVisibleThroughCurrentRenderPass(portal, level)) continue;
            if (!isCanonicalEntranceFace(portal, candidates)) continue;

            Vec3 origin = portal.getOriginPos();
            Vec3 portalNormal = portal.getNormal();

            // A two-sided portal has coplanar, opposite-facing entities. Only the
            // face whose source side contains this sub-level may establish a source
            // clip. The retained latch below owns the brief post-crossing interval.
            if (!portal.isInFrontOfPortal(center)) continue;

            // Straddle check: compute signed distance from each AABB corner to the
            // plane; if signs differ, the box crosses the plane.
            if (!boxStraddlesPlane(box, origin, portalNormal)) continue;

            // Rectangle check: the plane is INFINITE but the portal is a
            // FINITE rectangle. An airship that crosses the plane but sits
            // off to the side (beyond the portal's width/height) must NOT
            // be clipped. Project the AABB onto the portal's width (axisW)
            // and height (axisH) axes and require overlap with the portal's
            // [-width/2, width/2] x [-height/2, height/2] extent. Without
            // this, clipping engaged whenever the airship was merely near
            // the plane, even when nowhere near the actual portal opening.
            if (!boxOverlapsPortalRect(box, origin, portal.getAxisW(), portal.getAxisH(),
                    portal.getWidth(), portal.getHeight())) {
                continue;
            }

            anyStraddle = true;

            // Orient the normal so the kept half-space contains the sub-level's
            // center. IP creates two portal entities per physical frame, with
            // opposite normals; this makes the selected face's direction stable.
            double centerDot =
                (center.x - origin.x) * portalNormal.x +
                (center.y - origin.y) * portalNormal.y +
                (center.z - origin.z) * portalNormal.z;
            Vec3 orientedNormal = centerDot < 0 ? portalNormal.scale(-1.0) : portalNormal;

            // Rect-clamped distance, NOT origin distance: a dimension-stack global portal's
            // origin sits at (0, seamY, 0) — origin distance would lose to any entity portal
            // for ships away from the world axis even when the ship straddles the seam.
            double dist = portal.getDistanceToNearestPointInPortal(center);
            if (dist < bestDist || (dist == bestDist && best != null
                && portal.getUUID().compareTo(best.getUUID()) < 0)) {
                bestDist = dist;
                best = portal;
                bestPlane = new Plane(origin, orientedNormal);
            }
        }

        // A local render pose can leave the plane one or more network frames before the
        // server's parent-flip reaches this client. Keep the last split while the whole ship
        // has crossed into its destination half; the projection then remains visible until the
        // handoff switches this same object to normal destination rendering. A full return to
        // the source half is a genuine aborted crossing and releases the latch.
        if (!anyStraddle) {
            ClipDecision latched = CROSSING_LATCHES.get(sub.getUniqueId());
            if (latched != null && !latched.portal().isRemoved()
                && isPortalVisibleThroughCurrentRenderPass(latched.portal(), level)) {
                if (maxSignedDistance(box, latched.plane().pos(), latched.plane().normal()) <= 0.0) {
                    return latched;
                }
            }
            if (latched != null && !isPortalVisibleThroughCurrentRenderPass(latched.portal(), level)) {
                return null;
            }
            CROSSING_LATCHES.remove(sub.getUniqueId());
            SubLevelPortalContactTracker.clearContact(sub.getUniqueId());
            return null;
        }

        if (best == null) return null;

        // Stabilize the chosen normal across the contact session: lock in
        // whatever orientation we picked on first contact and reuse it on
        // subsequent frames. This prevents AABB-center wobble (especially
        // from airship rotation) from flipping the kept/culled halves
        // partway through a transit. See SubLevelPortalContactTracker for
        // rationale + lifecycle.
        Vec3 stableNormal = SubLevelPortalContactTracker.recordContact(
            sub.getUniqueId(), bestPlane.normal()
        );
        if (stableNormal != bestPlane.normal()) {
            bestPlane = new Plane(bestPlane.pos(), stableNormal);
        }

        ClipDecision decision = new ClipDecision(best, bestPlane);
        CROSSING_LATCHES.put(sub.getUniqueId(), decision);
        return decision;
    }

    /**
     * In a portal pass, this level contains all loaded destination portals. A
     * source clip may use one only when its finite aperture lies in the active
     * portal's destination view volume. Otherwise an off-screen portal in the
     * loaded destination dimension can cut Sable geometry inside an unrelated
     * portal view.
     */
    private static boolean isPortalVisibleThroughCurrentRenderPass(
        Portal portal, ClientLevel level
    ) {
        if (!PortalRendering.isRendering()) {
            return true;
        }
        Portal activePortal = PortalRendering.getRenderingPortal();
        if (activePortal.getDestDim() != level.dimension()) {
            return true;
        }

        AABB aperture = portal.getThinBoundingBox();
        Plane clippingPlane = PortalRendering.getActiveClippingPlane();
        if (clippingPlane != null && maxSignedDistance(
            aperture, clippingPlane.pos(), clippingPlane.normal()
        ) <= 0.0) {
            return false;
        }

        Vec3 cameraPos = PortalRendering.getRenderingCameraPos();
        BoxPredicateF innerFrustum = activePortal.getPortalShape()
            .getInnerFrustumCullingFunc(activePortal, cameraPos);
        if (innerFrustum == null) {
            return true;
        }

        // IP's inner-frustum predicate receives a box relative to the virtual
        // destination camera, not absolute destination-world coordinates.
        return !innerFrustum.test(
            (float) (aperture.minX - cameraPos.x),
            (float) (aperture.minY - cameraPos.y),
            (float) (aperture.minZ - cameraPos.z),
            (float) (aperture.maxX - cameraPos.x),
            (float) (aperture.maxY - cameraPos.y),
            (float) (aperture.maxZ - cameraPos.z)
        );
    }

    /** Parent flip completed: destination rendering now owns this sub-level. */
    public static void clearCrossingLatch(UUID subLevelId) {
        CROSSING_LATCHES.remove(subLevelId);
        SubLevelPortalContactTracker.clearContact(subLevelId);
    }

    /** Keep render-side portal companion selection identical to transit selection. */
    private static boolean isCanonicalEntranceFace(Portal portal, java.util.List<Portal> candidates) {
        Boolean cached = ipl.sable.client.IplStraddleRenderCache.canonicalPortalFace(portal);
        if (cached != null) return cached;
        for (Portal other : candidates) {
            if (other == portal || !portal.getDestDim().equals(other.getDestDim())) continue;
            if (portal.getOriginPos().distanceToSqr(other.getOriginPos()) > 1.0e-12
                || portal.getDestPos().distanceToSqr(other.getDestPos()) > 1.0e-12
                || Math.abs(portal.getWidth() - other.getWidth()) > 1.0e-6
                || Math.abs(portal.getHeight() - other.getHeight()) > 1.0e-6
                || portal.getNormal().dot(other.getNormal()) < 0.999999) {
                continue;
            }
            if (portal.getUUID().compareTo(other.getUUID()) > 0) {
                ipl.sable.client.IplStraddleRenderCache.cacheCanonicalPortalFace(portal, false);
                return false;
            }
        }
        ipl.sable.client.IplStraddleRenderCache.cacheCanonicalPortalFace(portal, true);
        return true;
    }

    /**
     * Is the airship's AABB center within the portal's finite rectangle
     * (laterally), i.e. is the airship actually in the doorway?
     *
     * <p>Projects the vector from the portal origin (== rect center, by IP
     * convention) to the AABB center onto the portal's width axis (axisW) and
     * height axis (axisH). Requires both projections to fall within the rect's
     * half-extent, expanded by {@link #RECT_MARGIN_FACTOR}.
     *
     * <p><b>Why center-based, not AABB-overlap:</b> the previous version used
     * the airship's full AABB lateral extent as an overlap radius. For a large
     * airship that radius is huge, so the test passed whenever the bounding box
     * merely brushed the portal's lateral span -- clipping engaged when the
     * airship was near the plane but laterally <em>beside</em> the opening, not
     * passing through it. Anchoring on the center means the gate opens only when
     * the airship's body is genuinely in the opening, which is the moment a
     * single clip plane is the right tool. Trade-off: an airship much larger
     * than the portal that transits off-center won't clip until its center
     * reaches the opening; acceptable since Sable setups size the portal to the
     * airship, and {@link #RECT_MARGIN_FACTOR} is tunable if it bites.
     */
    private static boolean boxOverlapsPortalRect(
        AABB box, Vec3 origin, Vec3 axisW, Vec3 axisH, double width, double height
    ) {
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;

        double limitW = width * 0.5 * (1.0 + RECT_MARGIN_FACTOR);
        double limitH = height * 0.5 * (1.0 + RECT_MARGIN_FACTOR);

        double projW = (centerX - origin.x) * axisW.x
            + (centerY - origin.y) * axisW.y
            + (centerZ - origin.z) * axisW.z;
        if (Math.abs(projW) > limitW) return false;

        double projH = (centerX - origin.x) * axisH.x
            + (centerY - origin.y) * axisH.y
            + (centerZ - origin.z) * axisH.z;
        if (Math.abs(projH) > limitH) return false;

        return true;
    }

    /**
     * Does this AABB have corners on both sides of the plane defined by
     * (planePoint, planeNormal)? Uses exact center and projection-radius extrema.
     */
    private static boolean boxStraddlesPlane(AABB box, Vec3 planePoint, Vec3 planeNormal) {
        return minSignedDistance(box, planePoint, planeNormal) <= 0.0
            && maxSignedDistance(box, planePoint, planeNormal) > 0.0;
    }

    private static java.util.List<Portal> collectCandidates(ClientLevel level) {
        java.util.List<Portal> candidates = new java.util.ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Portal portal) candidates.add(portal);
        }
        candidates.addAll(
            qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage.getGlobalPortals(level));
        return candidates;
    }

    /** Exact AABB projection extrema without allocating corner or interval arrays. */
    private static double minSignedDistance(AABB box, Vec3 planePoint, Vec3 planeNormal) {
        return signedDistanceAtCenter(box, planePoint, planeNormal) - projectionRadius(box, planeNormal);
    }

    private static double maxSignedDistance(AABB box, Vec3 planePoint, Vec3 planeNormal) {
        return signedDistanceAtCenter(box, planePoint, planeNormal) + projectionRadius(box, planeNormal);
    }

    private static double signedDistanceAtCenter(AABB box, Vec3 planePoint, Vec3 planeNormal) {
        return ((box.minX + box.maxX) * 0.5 - planePoint.x) * planeNormal.x
            + ((box.minY + box.maxY) * 0.5 - planePoint.y) * planeNormal.y
            + ((box.minZ + box.maxZ) * 0.5 - planePoint.z) * planeNormal.z;
    }

    private static double projectionRadius(AABB box, Vec3 planeNormal) {
        return (box.maxX - box.minX) * 0.5 * Math.abs(planeNormal.x)
            + (box.maxY - box.minY) * 0.5 * Math.abs(planeNormal.y)
            + (box.maxZ - box.minZ) * 0.5 * Math.abs(planeNormal.z);
    }
}
