package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.transit.MirrorRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

import java.util.UUID;

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
        if (!(sub.getLevel() instanceof ClientLevel level)) return null;

        AABB box = sub.boundingBox().toMojang();
        Vec3 center = box.getCenter();

        Portal best = null;
        double bestDistSq = Double.MAX_VALUE;
        Plane bestPlane = null;
        boolean anyStraddle = false;

        // Iterate all client-side entities. Cheap enough for the small entity counts
        // typical in vanilla survival; if this shows up in profilers we can swap to
        // a chunk-section query around the sub-level's AABB.
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Portal portal)) continue;
            if (!portal.isTeleportable()) continue;

            Vec3 origin = portal.getOriginPos();
            Vec3 portalNormal = portal.getNormal();

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
            // center.
            //
            // Why: IP creates TWO portal entities per physical frame -- one for
            // each face, with opposite normals. The clip math is symmetric in
            // *direction* but our finder previously picked whichever entity had
            // the closer origin, so the chosen normal was non-deterministic with
            // respect to the airship's approach direction. Result: entering from
            // one side worked correctly, entering from the opposite side flipped
            // the clip and made the airship's source-side half culled instead of
            // the dest-side half.
            //
            // Anchoring on the sub-level center is correct because at any frame
            // where we're rendering a *straddling* sub-level, the AABB centroid
            // sits on the source side of the portal plane (transit fires the
            // moment the centroid crosses, after which the sub-level is no longer
            // in this dim). For mirrors in the dest dim, the same property holds
            // -- the mirror's center is on the dest-side of its own portal,
            // which is the side it should keep visible.
            //
            // This is NOT camera-aware; the previous attempt to do that was
            // wrong because portals are one-way views, so the clipped side is a
            // property of the portal/sub-level pair, not the camera position.
            double centerDot =
                (center.x - origin.x) * portalNormal.x +
                (center.y - origin.y) * portalNormal.y +
                (center.z - origin.z) * portalNormal.z;
            Vec3 orientedNormal = centerDot < 0 ? portalNormal.scale(-1.0) : portalNormal;

            // Mirror-specific adjustment. IP's portal mapping rotates the airship's
            // local frame by 180° around the portal axis when transforming source ->
            // dest, so the mirror's center ends up on the *opposite* side of the
            // dest-dim portal from where we semantically want kept. Concretely:
            //
            //   - Source airship straddling source portal: its center is on the
            //     source side, which is also where its visible (un-clipped) body
            //     should be. The center-toward orientation above is correct.
            //
            //   - Mirror straddling dest portal: its center is the source center
            //     mapped through the 180° rotation, which puts the center on the
            //     side that visually corresponds to "where the source airship's
            //     dest-side parts continue" -- i.e., the side that SHOULD be
            //     kept-visible (the part you see through the portal frame as the
            //     airship enters the dest dim). User reported the kept side
            //     swapping when entering the nether; this flip restores the
            //     intended invariant "kept half is the source-dim airship's side."
            //
            // Detection: in singleplayer the integrated server's MirrorRegistry
            // is in the same JVM as our client mixin, so we can do a read-only
            // lookup by sub-level UUID. Concurrent modifications on the server
            // thread are tolerable -- worst case we miss a transition tick and
            // pick the wrong orientation for one frame. For multiplayer we'd need
            // a sync packet; deferred until needed.
            if (isKnownMirror(sub.getUniqueId())) {
                orientedNormal = orientedNormal.scale(-1.0);
            }

            double distSq = origin.distanceToSqr(center);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = portal;
                bestPlane = new Plane(origin, orientedNormal);
            }
        }

        // Drop the contact cache if the sub-level no longer straddles ANY
        // portal -- next contact starts fresh.
        if (!anyStraddle) {
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

        return new ClipDecision(best, bestPlane);
    }

    /**
     * Singleplayer-only mirror detection: is {@code subLevelId} registered as a
     * kinematic mirror's UUID in the server-side {@link MirrorRegistry}? Returns
     * false on any throwable so multiplayer / dedicated-server setups (where the
     * registry isn't in this JVM) fall through to "treat as source airship".
     */
    private static boolean isKnownMirror(UUID subLevelId) {
        try {
            for (MirrorRegistry.MirrorEntry entry : MirrorRegistry.all()) {
                if (entry.mirrorUuid().equals(subLevelId)) return true;
            }
        } catch (Throwable t) {
            // Registry unavailable or concurrent modification -- fall through.
        }
        return false;
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
        Vec3 center = box.getCenter();
        Vec3 toCenter = center.subtract(origin);

        double limitW = width * 0.5 * (1.0 + RECT_MARGIN_FACTOR);
        double limitH = height * 0.5 * (1.0 + RECT_MARGIN_FACTOR);

        double projW = toCenter.x * axisW.x + toCenter.y * axisW.y + toCenter.z * axisW.z;
        if (Math.abs(projW) > limitW) return false;

        double projH = toCenter.x * axisH.x + toCenter.y * axisH.y + toCenter.z * axisH.z;
        if (Math.abs(projH) > limitH) return false;

        return true;
    }

    /**
     * Does this AABB have corners on both sides of the plane defined by
     * (planePoint, planeNormal)? Computes signed distance for each of the 8 corners
     * and returns true iff at least one is positive AND at least one is non-positive.
     */
    private static boolean boxStraddlesPlane(AABB box, Vec3 planePoint, Vec3 planeNormal) {
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        boolean anyPos = false, anyNonPos = false;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    double signedDist =
                        (x - planePoint.x) * planeNormal.x +
                        (y - planePoint.y) * planeNormal.y +
                        (z - planePoint.z) * planeNormal.z;
                    if (signedDist > 0) anyPos = true;
                    else anyNonPos = true;
                    if (anyPos && anyNonPos) return true;
                }
            }
        }
        return false;
    }
}
