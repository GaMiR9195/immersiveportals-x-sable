package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic staff-beam geometry.
 *
 * <p>One {@link Route} is resolved per beam per frame from stable inputs (staff tip, the
 * grabbed body's render pose, the live straddle session), then cut into world-tagged
 * {@link Segment}s at portal apertures. Design rules, each fixing a concrete failure of the
 * old implementation:
 *
 * <ul>
 *   <li><b>Never blank.</b> Aperture crossing uses IP's exact raytrace when the beam line
 *       passes through the portal quad, and otherwise CLAMPS the line-plane intersection
 *       into the aperture rectangle. A beam whose anchor sits deep inside/beyond a portal
 *       (or whose staff tip swings wide) bends at the aperture edge instead of
 *       disappearing.</li>
 *   <li><b>Retention.</b> While a drag is active, a transiently unresolvable route (session
 *       snapshot gap, portal entity not yet synced) reuses the last good geometry for a few
 *       ticks instead of flickering off.</li>
 *   <li><b>Light path.</b> For same-dimension sessions both the direct line and the
 *       through-portal split exist in one world; the shorter path wins, so the beam goes
 *       through the aperture exactly when the player is actually working through it, from
 *       either side (forward via the session portal, backward via its reverse).</li>
 *   <li><b>Anchor side is a plane test.</b> The beam targets the anchor's IMAGE iff the
 *       native anchor is past the portal plane in the crossing direction — the same seam the
 *       render clip uses, so the beam endpoint always lands on visible geometry. At the
 *       plane both representations coincide: switching routes is visually continuous.</li>
 * </ul>
 */
public final class IplStaffBeamRoutes {

    /** Ticks a stale-but-active beam keeps its last resolved geometry. */
    private static final int RETENTION_TICKS = 15;

    private record Kept(Route route, long gameTime) {}

    private static final Map<UUID, Kept> LAST = new HashMap<>();

    private IplStaffBeamRoutes() {}

    /** Whole physical beam path: staff world, staff tip, final-frame target, portals in order. */
    public record Route(Level staffLevel, Vec3 staffStart, Vec3 target, List<Portal> portals) {}

    /**
     * One world-frame piece of a route. {@code world} is where this piece physically lies;
     * fractions locate it on the whole beam for noise continuity; {@code prefix} is the
     * portal chain traversed before this piece (noise vectors rotate through it).
     */
    public record Segment(
        Level world, Vec3 start, Vec3 end,
        double startFraction, double endFraction,
        double totalLength, List<Portal> prefix
    ) {}

    /** Resolve with retention: build fresh, else bridge a short gap with the last geometry. */
    @Nullable
    public static Route resolve(
        UUID owner, Level staffLevel, Vec3 staffStart,
        ClientSubLevel sub, Vec3 localAnchor, float partialTick
    ) {
        long now = staffLevel.getGameTime();
        Route built = build(staffLevel, staffStart, sub, localAnchor, partialTick);
        if (built != null) {
            LAST.put(owner, new Kept(built, now));
            return built;
        }
        Kept kept = LAST.get(owner);
        if (kept == null) return null;
        if (now - kept.gameTime() > RETENTION_TICKS
            || kept.route().staffLevel() != staffLevel) {
            LAST.remove(owner);
            return null;
        }
        // Staff tip stays live during the bridge; target/portals hold the last good shape.
        return new Route(staffLevel, staffStart, kept.route().target(), kept.route().portals());
    }

    public static void forget(UUID owner) {
        LAST.remove(owner);
    }

    /**
     * Where the local player's staff should AIM: the first endpoint the beam heads toward —
     * the entrance aperture when the beam goes through a portal, or the joint itself for a
     * direct grab. Stock aims straight at the native joint, which points the staff at empty
     * space (or the wrong dimension) whenever the joint is reached through a portal. Returns
     * null to fall back to stock aiming.
     */
    @Nullable
    public static Vec3 staffAimPoint(Player player, ClientSubLevel sub, Vec3 localAnchor, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Route route = resolve(player.getUUID(), player.level(), eye, sub, localAnchor, partialTick);
        if (route == null) return null;
        List<Segment> segments = segments(route);
        return segments.isEmpty() ? null : segments.get(0).end();
    }

    public static void clearAll() {
        LAST.clear();
    }

    @Nullable
    private static Route build(
        Level staffLevel, Vec3 staffStart, ClientSubLevel sub, Vec3 localAnchor, float partialTick
    ) {
        Level parent = IplDimAgnostic.getParentLevel(sub);
        if (parent == null) return null;
        Vec3 nativeAnchor = sub.renderPose(partialTick).transformPosition(localAnchor);

        // Cross-dimension post-transit window: the body already flipped to the target
        // dimension while the player is still on the source side; route through the exact
        // recorded crossing portal until the player follows or releases.
        Portal transitPortal = IplStraddleStaffPick.transitRoutePortal(sub, staffLevel);
        if (transitPortal != null) {
            return new Route(staffLevel, staffStart, nativeAnchor, List.of(transitPortal));
        }

        Portal session = IplStraddleSessionStore.resolvePortal(sub);
        if (session != null && !session.isRemoved()) {
            IplStraddlePoseMap.StraddleMapping mapping =
                IplStraddlePoseMap.StraddleMapping.of(session);
            boolean anchorThrough = isPastPlane(session, nativeAnchor);
            boolean sameDim = session.getDestDim().equals(session.level().dimension());
            Vec3 mappedAnchor = mapping.mapPoint(nativeAnchor);

            if (staffLevel == parent) {
                if (sameDim) {
                    // Deterministic route from geometry — never a per-frame length compare
                    // (that was the target flip-flop). The beam ends at whichever
                    // representation is VISIBLE for the grabbed anchor (image past the plane,
                    // native otherwise), reached by the physically correct path for the
                    // player's side. Transitions happen exactly at plane crossings, where the
                    // two representations coincide, so they are visually continuous.
                    boolean playerThrough = isPastPlane(session, staffStart);
                    if (anchorThrough) {
                        return playerThrough
                            ? new Route(staffLevel, staffStart, mappedAnchor, List.of())
                            : new Route(staffLevel, staffStart, mappedAnchor, List.of(session));
                    }
                    if (playerThrough) {
                        Portal reverse = reverseOf(session, staffLevel);
                        if (reverse != null) {
                            return new Route(staffLevel, staffStart, nativeAnchor, List.of(reverse));
                        }
                    }
                    return new Route(staffLevel, staffStart, nativeAnchor, List.of());
                }
                // Cross-dimension, viewed from the body's native side.
                if (anchorThrough) {
                    return new Route(staffLevel, staffStart, mappedAnchor, List.of(session));
                }
                return new Route(staffLevel, staffStart, nativeAnchor, List.of());
            }

            // Player stands in the session's destination world (cross-dimension exit side).
            if (session.getDestinationWorld() == staffLevel) {
                if (anchorThrough) {
                    // The image is local to the player: straight beam to it.
                    return new Route(staffLevel, staffStart, mappedAnchor, List.of());
                }
                Portal reverse = reverseOf(session, staffLevel);
                if (reverse != null) {
                    return new Route(staffLevel, staffStart, nativeAnchor, List.of(reverse));
                }
                return null; // retention bridges until the reverse portal syncs
            }
        }

        // No session in play and the body is native to the player's world: straight beam.
        if (staffLevel == parent) {
            return new Route(staffLevel, staffStart, nativeAnchor, List.of());
        }

        // Remote grab through unrelated portals: replay the exact captured pick chain.
        List<Portal> captured = IplStraddleStaffPick.capturedPortals(sub.getUniqueId(), staffLevel);
        if (captured != null && !captured.isEmpty()) {
            Level end = staffLevel;
            for (Portal portal : captured) {
                end = portal.getDestinationWorld();
            }
            if (end == parent) {
                return new Route(staffLevel, staffStart, nativeAnchor, List.copyOf(captured));
            }
        }
        return null;
    }

    /**
     * Cut a route into per-world segments at portal apertures. Total length and fractions
     * are shared so noise animates continuously across the whole beam.
     */
    public static List<Segment> segments(Route route) {
        int count = route.portals().size();
        Vec3[] starts = new Vec3[count + 1];
        Vec3[] ends = new Vec3[count + 1];
        double[] lengths = new double[count + 1];

        Vec3 cursor = route.staffStart();
        for (int i = 0; i < count; i++) {
            Portal portal = route.portals().get(i);
            Vec3 aperture = clampToAperture(portal, cursor, endpointInFrame(route, i));
            starts[i] = cursor;
            ends[i] = aperture;
            lengths[i] = cursor.distanceTo(aperture);
            cursor = portal.transformPoint(aperture);
        }
        starts[count] = cursor;
        ends[count] = route.target();
        lengths[count] = cursor.distanceTo(route.target());

        double total = 0.0;
        for (double length : lengths) total += length;
        if (total <= 1.0e-9) return List.of();

        List<Segment> segments = new ArrayList<>(count + 1);
        Level world = route.staffLevel();
        double before = 0.0;
        for (int i = 0; i <= count; i++) {
            segments.add(new Segment(
                world, starts[i], ends[i],
                before / total, (before + lengths[i]) / total,
                total, List.copyOf(route.portals().subList(0, i))
            ));
            before += lengths[i];
            if (i < count) world = route.portals().get(i).getDestinationWorld();
        }
        return segments;
    }

    /**
     * Aperture point of the {@code from -> to} line on this portal. Exact quad raytrace when
     * the line passes through; otherwise the line-plane intersection clamped into the
     * aperture rectangle — a beam can bend at the portal edge but can never vanish.
     */
    public static Vec3 clampToAperture(Portal portal, Vec3 from, Vec3 to) {
        Vec3 exact = portal.rayTrace(from, to);
        if (exact != null) return exact;

        Vec3 origin = portal.getOriginPos();
        Vec3 normal = portal.getNormal();
        Vec3 direction = to.subtract(from);
        double denom = direction.dot(normal);
        Vec3 planePoint;
        if (Math.abs(denom) < 1.0e-9) {
            planePoint = from.subtract(normal.scale(from.subtract(origin).dot(normal)));
        } else {
            double t = Math.clamp(origin.subtract(from).dot(normal) / denom, 0.0, 1.0);
            planePoint = from.add(direction.scale(t));
        }
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();
        Vec3 rel = planePoint.subtract(origin);
        double w = Math.clamp(rel.dot(axisW), -portal.getWidth() * 0.5, portal.getWidth() * 0.5);
        double h = Math.clamp(rel.dot(axisH), -portal.getHeight() * 0.5, portal.getHeight() * 0.5);
        return origin.add(axisW.scale(w)).add(axisH.scale(h));
    }

    /** True when {@code point} is past the portal plane in the crossing direction (-normal). */
    public static boolean isPastPlane(Portal portal, Vec3 point) {
        return point.subtract(portal.getOriginPos()).dot(portal.getNormal()) < -1.0e-4;
    }

    @Nullable
    private static Portal reverseOf(Portal session, Level expectedLevel) {
        Portal reverse = PortalExtension.get(session).reversePortal;
        if (reverse == null || reverse.isRemoved() || reverse.level() != expectedLevel) {
            return null;
        }
        return reverse;
    }

    /** Route target expressed in the frame BEFORE portal {@code frame} (unmap the suffix). */
    private static Vec3 endpointInFrame(Route route, int frame) {
        Vec3 endpoint = route.target();
        for (int i = route.portals().size() - 1; i >= frame; i--) {
            endpoint = route.portals().get(i).inverseTransformPoint(endpoint);
        }
        return endpoint;
    }
}
