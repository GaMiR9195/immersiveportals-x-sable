package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

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

        // Iterate all client-side entities. Cheap enough for the small entity counts
        // typical in vanilla survival; if this shows up in profilers we can swap to
        // a chunk-section query around the sub-level's AABB.
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Portal portal)) continue;
            if (!portal.isTeleportable()) continue;

            Vec3 origin = portal.getOriginPos();
            Vec3 normal = portal.getNormal();

            // Straddle check: compute signed distance from each AABB corner to the
            // plane; if signs differ, the box crosses the plane.
            if (!boxStraddlesPlane(box, origin, normal)) continue;

            // Use portal.getNormal() AS-IS. The kept half-space is always the
            // source-side of the portal plane (the side where the source airship
            // physically lives in source-dim coords), regardless of where the
            // camera is. This is correct because portals are one-way views:
            //   - Camera on the source side: kept = camera side. Airship visible
            //     on source side, dest-side half culled (mirror takes over via
            //     the portal-through view).
            //   - Camera circled around to the back of the portal frame: kept =
            //     far-from-camera side. Airship's dest-side half (which is now
            //     on the camera side) is culled, so it doesn't show "through" the
            //     back of the portal frame. The source-side half is still hidden
            //     by the obsidian wall of the frame, so the airship effectively
            //     vanishes from this angle (the desired one-way behavior).
            // After the airship transits, it's a sub-level in the dest dim
            // straddling the dest-dim portal. That portal's natural normal points
            // outward from the dest face, which is again the correct "source-side"
            // of its own plane for the post-transit airship -- no flip needed
            // there either.

            double distSq = origin.distanceToSqr(center);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = portal;
                bestPlane = new Plane(origin, normal);
            }
        }

        return best == null ? null : new ClipDecision(best, bestPlane);
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
