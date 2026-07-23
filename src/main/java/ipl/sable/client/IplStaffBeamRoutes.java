package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplGrabLink;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Deterministic staff-beam geometry, driven by the grab chain.
 *
 * <p>The beam is the visible expression of the SAME frame algebra the server uses for the
 * PD goal: the owner's grab chain ({@link IplGrabChainClient}), plus one refinement for a
 * straddling body — when the grabbed anchor is past a live straddle-session plane, its
 * visible representation is the portal IMAGE, so the route gains that session link and
 * targets the image. Both facts are event-sourced (chain) or server-synced (session
 * store); nothing is chosen by comparing distances, latching first-seen portals, or
 * testing which side the player "probably" works from. The old implementation's
 * SESSION_PIN latch, per-frame length comparisons, and transit-frame plumbing are gone.
 *
 * <p>Route transitions are continuous by construction: a chain link appears exactly when
 * a teleport/transit event maps the endpoint through that same portal, and the image
 * refinement toggles exactly at the session plane, where native and image coincide.
 *
 * <p>Kept from the previous implementation because they are exact, not heuristic:
 * aperture clamping (a beam may bend at a portal edge but never vanish), segment
 * cutting with shared fractions (noise animates continuously across worlds), and short
 * retention across transient resolution gaps (session snapshot latency).
 */
public final class IplStaffBeamRoutes {

    /** Ticks a stale-but-active beam keeps its last resolved geometry. */
    private static final int RETENTION_TICKS = 15;

    private record Kept(Route route, long gameTime) {}

    private static final Map<UUID, Kept> LAST = new HashMap<>();

    /** Owner -> latest true physical route length (feeds PhysicsBeam node density). */
    private static final Map<UUID, Double> LENGTHS = new HashMap<>();

    /** Beam object -> owner, registered by the renderer/creation hook (weak, identity). */
    private static final Map<Object, UUID> BEAM_OWNERS = new WeakHashMap<>();

    private IplStaffBeamRoutes() {}

    /**
     * Whole physical beam path.
     *
     * @param links         frame hops from the staff world to the target's frame
     * @param target        beam endpoint, expressed in the FINAL frame (after all links)
     * @param imageRotation rotation of the straddle-session image refinement when the
     *                      target is an image, identity otherwise — combined with the
     *                      chain fold it defines the mouse-rotation axis frame
     */
    public record Route(
        Level staffLevel, Vec3 staffStart, Vec3 target,
        List<IplGrabLink> links, Quaterniond imageRotation, UUID owner
    ) {}

    /**
     * One world-frame piece of a route. {@code dim} is where this piece physically lies;
     * fractions locate it on the whole beam for noise continuity; {@code noiseRotation}
     * is the composed rotation of the links traversed before this piece (noise vectors
     * rotate with the path); {@code prefixPortalIds} matches IP's render path by UUID.
     */
    public record Segment(
        ResourceKey<Level> dim, Vec3 start, Vec3 end,
        double startFraction, double endFraction,
        double totalLength, Quaterniond noiseRotation, List<UUID> prefixPortalIds
    ) {}

    // ------------------------------------------------------------------
    // Resolution.
    // ------------------------------------------------------------------

    /** Resolve with retention: build fresh, else bridge a short gap with the last geometry. */
    @Nullable
    public static Route resolve(
        UUID owner, Level staffLevel, Vec3 staffStart,
        ClientSubLevel sub, Vec3 localAnchor, float partialTick
    ) {
        long now = staffLevel.getGameTime();
        Route built = build(owner, staffLevel, staffStart, sub, localAnchor, partialTick);
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
        // Staff tip stays live during the bridge; target/links hold the last good shape.
        return new Route(staffLevel, staffStart, kept.route().target(),
            kept.route().links(), kept.route().imageRotation(), owner);
    }

    public static void forget(UUID owner) {
        LAST.remove(owner);
        LENGTHS.remove(owner);
    }

    public static void clearAll() {
        LAST.clear();
        LENGTHS.clear();
    }

    @Nullable
    private static Route build(
        UUID owner, Level staffLevel, Vec3 staffStart,
        ClientSubLevel sub, Vec3 localAnchor, float partialTick
    ) {
        Level parent = IplDimAgnostic.getParentLevel(sub);
        if (parent == null) return null;
        Vec3 nativeAnchor = sub.renderPose(partialTick).transformPosition(localAnchor);

        List<IplGrabLink> links = IplGrabChainClient.chainFor(owner, sub.getUniqueId());
        Vec3 target = nativeAnchor;
        Quaterniond imageRotation = new Quaterniond();
        boolean targetsImage = false;

        // Straddle refinement: while the body straddles a session portal and the grabbed
        // anchor is past that portal's plane (crossing direction), the anchor's visible
        // representation is the portal image — the beam must thread the aperture and end
        // on visible geometry. At the plane both representations coincide, so toggling
        // this refinement is visually continuous. Session order is server history
        // (session-start order), not a per-frame race.
        for (Portal session : IplStraddleSessionStore.resolveAllPortals(sub)) {
            if (session.isRemoved()) continue;
            if (!isPastPlane(session, nativeAnchor)) continue;
            IplGrabLink sessionLink = IplGrabLink.forward(session);
            links = IplGrabLink.append(links, sessionLink);
            target = sessionLink.transform(nativeAnchor);
            imageRotation = new Quaterniond(sessionLink.rotation());
            targetsImage = true;
            break;
        }

        // Frame-continuity validation: the route must start in the staff world and each
        // link must continue where the previous one ended. A mismatch is a transient
        // desync (snapshot in flight during a hop) — return null and let retention
        // bridge it rather than drawing a wrong-frame beam.
        ResourceKey<Level> at = staffLevel.dimension();
        for (IplGrabLink link : links) {
            if (!link.fromDim().equals(at)) return null;
            at = link.toDim();
        }
        if (!targetsImage && !at.equals(parent.dimension())) return null;

        return new Route(staffLevel, staffStart, target, links, imageRotation, owner);
    }

    /**
     * Where the local player's staff should AIM: the first endpoint the beam heads toward —
     * the entrance aperture when the beam goes through a portal, or the joint itself for a
     * direct grab. Returns null to fall back to stock aiming.
     */
    @Nullable
    public static Vec3 staffAimPoint(Player player, ClientSubLevel sub, Vec3 localAnchor, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Route route = resolve(player.getUUID(), player.level(), eye, sub, localAnchor, partialTick);
        if (route == null) return null;
        List<Segment> segments = segments(route);
        return segments.isEmpty() ? null : segments.get(0).end();
    }

    /**
     * The frame conversion for the mouse-rotation axis of this grab: the composed route
     * rotation (chain fold, then the image refinement inverse — see the derivation in
     * {@code IplStraddleStaffPick.unmapStaffInputAxis}). Null when no route resolves.
     */
    @Nullable
    public static Quaterniond axisRotation(Player player, ClientSubLevel sub, Vec3 localAnchor) {
        Route route = resolve(
            player.getUUID(), player.level(), player.getEyePosition(), sub, localAnchor, 1.0f);
        if (route == null) return null;
        Quaterniond folded = new Quaterniond();
        for (IplGrabLink link : route.links()) {
            folded.premul(link.rotation());
        }
        // axis_constraint = M^-1 * R_route * axis_player
        return new Quaterniond(route.imageRotation()).conjugate().mul(folded);
    }

    // ------------------------------------------------------------------
    // Segments.
    // ------------------------------------------------------------------

    /**
     * Cut a route into per-world segments at portal apertures. Total length and fractions
     * are shared so noise animates continuously across the whole beam. Also publishes the
     * owner's true route length for PhysicsBeam node density.
     */
    public static List<Segment> segments(Route route) {
        List<IplGrabLink> links = route.links();
        int count = links.size();
        Vec3[] starts = new Vec3[count + 1];
        Vec3[] ends = new Vec3[count + 1];
        double[] lengths = new double[count + 1];

        Vec3 cursor = route.staffStart();
        for (int i = 0; i < count; i++) {
            IplGrabLink link = links.get(i);
            Vec3 aperture = clampToAperture(link, cursor, endpointInFrame(route, i));
            starts[i] = cursor;
            ends[i] = aperture;
            lengths[i] = cursor.distanceTo(aperture);
            cursor = link.transform(aperture);
        }
        starts[count] = cursor;
        ends[count] = route.target();
        lengths[count] = cursor.distanceTo(route.target());

        double total = 0.0;
        for (double length : lengths) total += length;
        if (total <= 1.0e-9) return List.of();
        LENGTHS.put(route.owner(), total);

        List<Segment> segments = new ArrayList<>(count + 1);
        ResourceKey<Level> dim = route.staffLevel().dimension();
        Quaterniond noiseRotation = new Quaterniond();
        List<UUID> prefix = List.of();
        double before = 0.0;
        for (int i = 0; i <= count; i++) {
            segments.add(new Segment(
                dim, starts[i], ends[i],
                before / total, (before + lengths[i]) / total,
                total, new Quaterniond(noiseRotation), prefix
            ));
            before += lengths[i];
            if (i < count) {
                IplGrabLink link = links.get(i);
                dim = link.toDim();
                noiseRotation = new Quaterniond(link.rotation()).mul(noiseRotation);
                List<UUID> next = new ArrayList<>(prefix);
                next.add(link.portalId());
                prefix = List.copyOf(next);
            }
        }
        return segments;
    }

    /** Route target expressed in the frame BEFORE link {@code frame} (unfold the suffix). */
    private static Vec3 endpointInFrame(Route route, int frame) {
        Vec3 endpoint = route.target();
        List<IplGrabLink> links = route.links();
        for (int i = links.size() - 1; i >= frame; i--) {
            endpoint = links.get(i).inverseTransform(endpoint);
        }
        return endpoint;
    }

    /**
     * Aperture point of the {@code from -> to} line on this link's doorway. Uses the LIVE
     * portal entity's exact quad raytrace when it is present and still where the snapshot
     * says (a moved portal invalidates the snapshot's doorway, not the frame mapping);
     * otherwise intersects the snapshot plane and clamps into the snapshot rectangle —
     * a beam can bend at the portal edge but can never vanish.
     */
    public static Vec3 clampToAperture(IplGrabLink link, Vec3 from, Vec3 to) {
        Portal live = findLivePortal(link);
        if (live != null) {
            Vec3 exact = live.rayTrace(from, to);
            if (exact != null) return exact;
        }

        Vec3 origin = link.origin();
        Vec3 normal = link.normal();
        Vec3 direction = to.subtract(from);
        double denom = direction.dot(normal);
        Vec3 planePoint;
        if (Math.abs(denom) < 1.0e-9) {
            planePoint = from.subtract(normal.scale(from.subtract(origin).dot(normal)));
        } else {
            double t = Math.clamp(origin.subtract(from).dot(normal) / denom, 0.0, 1.0);
            planePoint = from.add(direction.scale(t));
        }
        Vec3 rel = planePoint.subtract(origin);
        double w = Math.clamp(rel.dot(link.axisW()), -link.width() * 0.5, link.width() * 0.5);
        double h = Math.clamp(rel.dot(link.axisH()), -link.height() * 0.5, link.height() * 0.5);
        return origin.add(link.axisW().scale(w)).add(link.axisH().scale(h));
    }

    /** Live portal for a link: matched by UUID AND verified against the snapshot origin. */
    @Nullable
    private static Portal findLivePortal(IplGrabLink link) {
        for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            if (!level.dimension().equals(link.fromDim())) continue;
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof Portal portal && !portal.isRemoved()
                    && portal.getUUID().equals(link.portalId())
                    && portal.getOriginPos().distanceToSqr(link.origin()) < 0.25) {
                    return portal;
                }
            }
            for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
                if (!portal.isRemoved() && portal.getUUID().equals(link.portalId())
                    && portal.getOriginPos().distanceToSqr(link.origin()) < 0.25) {
                    return portal;
                }
            }
        }
        return null;
    }

    /** True when {@code point} is past the portal plane in the crossing direction (-normal). */
    public static boolean isPastPlane(Portal portal, Vec3 point) {
        return point.subtract(portal.getOriginPos()).dot(portal.getNormal()) < -1.0e-4;
    }

    // ------------------------------------------------------------------
    // PhysicsBeam length feed (true node density, issue: wrong segment count).
    // ------------------------------------------------------------------

    /** Bind a beam object to its owner so the per-tick update can find its route length. */
    public static void registerBeamOwner(Object beam, UUID owner) {
        BEAM_OWNERS.put(beam, owner);
    }

    /** The latest true route length for this beam object, or NaN when unknown. */
    public static double knownLengthFor(Object beam) {
        UUID owner = BEAM_OWNERS.get(beam);
        if (owner == null) return Double.NaN;
        Double length = LENGTHS.get(owner);
        return length == null ? Double.NaN : length;
    }

    /** The latest true route length for this owner, or NaN when unknown. */
    public static double knownLength(UUID owner) {
        Double length = LENGTHS.get(owner);
        return length == null ? Double.NaN : length;
    }

    /** Rotate a vector by a quaternion (helper for noise mapping in the render mixin). */
    public static Vec3 rotate(Quaterniond rotation, Vec3 v) {
        Vector3d out = rotation.transform(new Vector3d(v.x, v.y, v.z));
        return new Vec3(out.x, out.y, out.z);
    }
}
