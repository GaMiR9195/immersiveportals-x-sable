package ipl.sable.client;

import ipl.sable.transit.IplGrabLink;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual route of a staff beam: the portals the grab actually went through, minus every detour
 * that leads back to where it started.
 *
 * <p>The grab chain is event history (player crossings prepend, body transits append) and only
 * adjacent inverse pairs annihilate there. Drawing it link by link made the beam behave like a
 * rope: out through one face and back through another, or around a portal loop, and the beam
 * stayed threaded through every portal in the order the events happened. Any stretch of the
 * chain whose links compose to the identity (and ends in the world it starts in) is such a
 * detour: removing it changes neither the end point nor the pull, because the motor goal is the
 * cursor point folded through the chain and the stretch folds to nothing. The whole chain
 * composing to the identity leaves a straight beam.
 *
 * <p>Only links of the chain itself are used -- no portal is ever added. Render-side only: the
 * authoritative chain, the server goal and the rotation axis frame are untouched.
 */
public final class IplBeamRouteSolver {

    /** Compared at points around the probe: rounding of equivalent snapshots stays far below. */
    private static final double MAP_EPSILON = 1.0e-3;
    private static final double ROTATION_DOT_EPSILON = 1.0e-8;
    private static final double SCALE_EPSILON = 1.0e-6;

    private IplBeamRouteSolver() {}

    /**
     * @param links validated frame hops from the staff world to the target's frame
     * @param start staff tip, in the staff world (the probe for the identity test)
     */
    public static List<IplGrabLink> solve(List<IplGrabLink> links, Vec3 start) {
        if (links.size() < 2) return links;
        List<IplGrabLink> reduced = new ArrayList<>(links);
        boolean removed;
        do {
            removed = removeIdentityStretch(reduced, start);
        } while (removed);
        return List.copyOf(reduced);
    }

    /**
     * Remove the first (and, from that link, the longest) stretch that composes to the identity.
     * The identity is tested around the staff tip carried into the stretch's own frame.
     *
     * @return whether a stretch was removed
     */
    private static boolean removeIdentityStretch(List<IplGrabLink> links, Vec3 start) {
        Vec3 probe = start;
        for (int i = 0; i < links.size(); i++) {
            for (int j = links.size() - 1; j > i; j--) {
                if (!links.get(i).fromDim().equals(links.get(j).toDim())) continue;
                List<IplGrabLink> stretch = links.subList(i, j + 1);
                if (Iso.of(stretch).isIdentityAround(probe)) {
                    stretch.clear();
                    return true;
                }
            }
            probe = links.get(i).transform(probe);
        }
        return false;
    }

    /** Similarity map {@code p -> rotation * (scale * p) + translation}. */
    private record Iso(Quaterniond rotation, double scale, Vector3d translation) {

        static Iso of(List<IplGrabLink> links) {
            Iso result = new Iso(new Quaterniond(), 1.0, new Vector3d());
            for (IplGrabLink link : links) result = result.then(link);
            return result;
        }

        /** This map followed by {@code link} ({@code p -> dest + R * (s * (p - origin))}). */
        Iso then(IplGrabLink link) {
            Quaterniond r = new Quaterniond(link.rotation()).mul(rotation);
            double s = scale * link.scale();
            Vector3d t = new Vector3d(translation).sub(link.origin().x, link.origin().y, link.origin().z)
                .mul(link.scale());
            link.rotation().transform(t);
            t.add(link.dest().x, link.dest().y, link.dest().z);
            return new Iso(r, s, t);
        }

        Vec3 apply(Vec3 p) {
            Vector3d v = new Vector3d(p.x, p.y, p.z).mul(scale);
            rotation.transform(v);
            v.add(translation);
            return new Vec3(v.x, v.y, v.z);
        }

        /** The identity in the neighbourhood that matters (probe plus unit offsets). */
        boolean isIdentityAround(Vec3 probe) {
            if (Math.abs(scale - 1.0) > SCALE_EPSILON) return false;
            Quaterniond q = new Quaterniond(rotation).normalize();
            if (1.0 - Math.abs(q.w) > ROTATION_DOT_EPSILON) return false;
            Vec3[] points = {probe, probe.add(1, 0, 0), probe.add(0, 1, 0), probe.add(0, 0, 1)};
            for (Vec3 point : points) {
                if (apply(point).distanceToSqr(point) > MAP_EPSILON * MAP_EPSILON) return false;
            }
            return true;
        }
    }
}
