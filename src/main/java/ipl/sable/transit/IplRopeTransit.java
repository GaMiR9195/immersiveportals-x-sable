package ipl.sable.transit;

import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Portal routing of free rope points (native ABI 8).
 *
 * <p>A rope point is an ordinary free rigid body of its strand's chart. When the body a rope
 * hangs from is half through a portal, the points that followed it through stay where
 * physics put them -- behind the portal plane in the SOURCE level, inside the wall the portal
 * is cut into. Colliding there, they pushed the ship off phantom ground. This class watches
 * every point's own path (no assumptions about which mod owns the rope): a point whose
 * segment between two samples passes a portal rectangle gets a route hop, and its collider
 * is re-targeted to the destination chart through the composed portal isometry, exactly
 * like a body image. Passing back through the same rectangle pops the hop. The point body,
 * its joints and the owning mod's logic and rendering stay untouched.
 */
public final class IplRopeTransit {
    /** One portal pass: {@code mapping} takes the frame before the hop to the frame after it. */
    private record Hop(IplStraddlePoseMap.StraddleMapping mapping, ServerLevel to,
                       Vec3 origin, Vec3 normal, Vec3 axisW, double halfW, Vec3 axisH, double halfH,
                       Portal portal) {
        static Hop of(Portal portal, ServerLevel to) {
            return new Hop(IplStraddlePoseMap.StraddleMapping.of(portal), to, portal.getOriginPos(),
                portal.getNormal(), portal.getAxisW(), portal.getWidth() * 0.5,
                portal.getAxisH(), portal.getHeight() * 0.5, portal);
        }

        /** The way back from {@code portal}'s exit: entered from the exit side. */
        static Hop reverseOf(Portal portal, ServerLevel back) {
            var m = IplStraddlePoseMap.StraddleMapping.of(portal);
            return new Hop(m.inverse(), back, m.mapPoint(portal.getOriginPos()),
                m.mapVec(portal.getNormal()).reverse(), m.mapVec(portal.getAxisW()), portal.getWidth() * 0.5,
                m.mapVec(portal.getAxisH()), portal.getHeight() * 0.5, null);
        }

        double side(Vec3 p) {
            return p.subtract(origin).dot(normal);
        }

        /** Whether segment {@code a -> b} passes the rectangle (signs already checked). */
        boolean passes(Vec3 a, Vec3 b, double margin) {
            double sa = side(a), sb = side(b);
            double t = sa / (sa - sb);
            Vec3 hit = a.lerp(b, t).subtract(origin);
            return Math.abs(hit.dot(axisW)) <= halfW + margin && Math.abs(hit.dot(axisH)) <= halfH + margin;
        }

        boolean sameTransit(Portal other) {
            return to.dimension().equals(other.getDestDim())
                && mapping.sameAs(IplStraddlePoseMap.StraddleMapping.of(other));
        }
    }

    private static final class Strand {
        final List<List<Hop>> routes = new ArrayList<>();
        double[] previous = new double[0];
    }

    private static final Map<ServerLevel, Map<Long, Strand>> STRANDS = new WeakHashMap<>();

    private IplRopeTransit() {}

    public static boolean supported() {
        return IplRapierNatives.abiLevel() >= IplRapierNatives.ROPE_ROUTE_ABI;
    }

    /** Called once per physics sample of a level, after the step. */
    public static void sample(ServerLevel level) {
        if (!supported()) return;
        long scene = IplScenes.sceneHandle(level);
        if (scene == 0) return;
        long[] ids = IplRapierNatives.ropeIds(scene);
        Map<Long, Strand> strands = STRANDS.computeIfAbsent(level, ignored -> new HashMap<>());
        if (ids.length == 0) {
            strands.clear();
            return;
        }
        Set<Long> live = new HashSet<>(ids.length);
        for (long id : ids) {
            live.add(id);
            double[] now = IplRapierNatives.ropePointPositions(scene, id);
            if (now.length == 0) continue;
            Strand strand = strands.computeIfAbsent(id, ignored -> new Strand());
            resize(strand, now);
            advance(level, scene, id, strand, now);
            strand.previous = now;
        }
        strands.keySet().retainAll(live);
    }

    /**
     * Same-dimension group flip moved every particle of the given ropes by {@code portal}'s
     * isometry. A point that had passed that portal is now really where its image was; every
     * other point now lies on the far side and keeps its old collisions through the way back.
     */
    public static void onRopesMoved(ServerLevel level, Set<Long> ropeIds, Portal portal) {
        if (!supported() || ropeIds.isEmpty()) return;
        Map<Long, Strand> strands = STRANDS.get(level);
        long scene = IplScenes.sceneHandle(level);
        if (strands == null || scene == 0) return;
        var mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
        for (long id : ropeIds) {
            Strand strand = strands.get(id);
            if (strand == null) continue;
            for (int i = 0; i < strand.routes.size(); i++) {
                List<Hop> route = strand.routes.get(i);
                if (!route.isEmpty() && route.getFirst().sameTransit(portal)) route.removeFirst();
                else route.addFirst(Hop.reverseOf(portal, level));
                Vec3 moved = mapping.mapPoint(point(strand.previous, i));
                strand.previous[3 * i] = moved.x;
                strand.previous[3 * i + 1] = moved.y;
                strand.previous[3 * i + 2] = moved.z;
                apply(scene, id, i, route, level);
            }
        }
    }

    public static void clearAll() {
        STRANDS.clear();
    }

    /** Simulated winches add/remove points at the START of a strand. */
    private static void resize(Strand strand, double[] now) {
        int count = now.length / 3;
        int known = strand.routes.size();
        if (count == known) return;
        double[] previous = new double[now.length];
        if (count > known) {
            for (int i = 0; i < count - known; i++) strand.routes.addFirst(new ArrayList<>());
            System.arraycopy(now, 0, previous, 0, 3 * (count - known));
            System.arraycopy(strand.previous, 0, previous, 3 * (count - known), strand.previous.length);
        } else {
            for (int i = 0; i < known - count; i++) strand.routes.removeFirst();
            System.arraycopy(strand.previous, 3 * (known - count), previous, 0, now.length);
        }
        strand.previous = previous;
    }

    private static void advance(ServerLevel level, long scene, long id, Strand strand, double[] now) {
        int count = now.length / 3;
        // One portal query per distinct route of the strand (same frame, same mapping).
        Map<List<Hop>, List<Portal>> portalCache = new HashMap<>();
        for (int i = 0; i < count; i++) {
            List<Hop> route = strand.routes.get(i);
            Vec3 a = point(strand.previous, i), b = point(now, i);
            if (a.distanceToSqr(b) < 1.0e-12) continue;
            boolean changed = false;
            // Back through the last hop's rectangle (from its passed side).
            if (!route.isEmpty()) {
                Hop last = route.getLast();
                Vec3 la = frame(route, route.size() - 1, a), lb = frame(route, route.size() - 1, b);
                if (last.side(la) < 0.0 && last.side(lb) >= 0.0 && last.passes(la, lb, 0.5)) {
                    route.removeLast();
                    changed = true;
                }
            }
            if (!changed) {
                ServerLevel frameLevel = route.isEmpty() ? level : route.getLast().to();
                Vec3 fa = frame(route, route.size(), a), fb = frame(route, route.size(), b);
                List<Portal> portals = portalCache.computeIfAbsent(List.copyOf(route),
                    r -> portalsAround(frameLevel, now, r));
                for (Portal portal : portals) {
                    if (portal.level() != frameLevel) continue;
                    if (!route.isEmpty() && route.getLast().portal() != null && isWayBack(route.getLast().portal(), portal))
                        continue; // handled by the pop above
                    ServerLevel to = level.getServer().getLevel(portal.getDestDim());
                    if (to == null) continue;
                    Hop hop = Hop.of(portal, to);
                    if (hop.side(fa) >= 0.0 && hop.side(fb) < 0.0 && hop.passes(fa, fb, 0.0)) {
                        route.addLast(hop);
                        cancelReturn(route, level);
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) apply(scene, id, i, route, level);
        }
    }

    /** A way-back hop right after its forward hop (or vice versa) is no route at all. */
    private static void cancelReturn(List<Hop> route, ServerLevel level) {
        int n = route.size();
        if (n < 2) return;
        ServerLevel before = n == 2 ? level : route.get(n - 3).to();
        if (route.get(n - 1).to() == before
            && route.get(n - 2).mapping().then(route.get(n - 1).mapping())
                .sameAs(IplStraddlePoseMap.StraddleMapping.identity())) {
            route.removeLast();
            route.removeLast();
        }
    }

    private static List<Portal> portalsAround(ServerLevel frameLevel, double[] raw, List<Hop> route) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < raw.length / 3; i++) {
            Vec3 p = frame(route, route.size(), point(raw, i));
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return IplPieceTracker.portalsNear(frameLevel, new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(2.0));
    }

    /** The exit-side face of {@code entered}: entering it means coming back. */
    private static boolean isWayBack(Portal entered, Portal candidate) {
        return candidate.getDestDim().equals(entered.level().dimension())
            && IplStraddlePoseMap.StraddleMapping.of(candidate)
                .sameAs(IplStraddlePoseMap.StraddleMapping.of(entered).inverse());
    }

    /** Raw strand-chart position seen through the first {@code hops} hops of the route. */
    private static Vec3 frame(List<Hop> route, int hops, Vec3 raw) {
        Vec3 p = raw;
        for (int i = 0; i < hops; i++) p = route.get(i).mapping().mapPoint(p);
        return p;
    }

    private static void apply(long scene, long id, int index, List<Hop> route, ServerLevel level) {
        if (route.isEmpty() || route.getLast().to() == level && composed(route).sameAs(
            IplStraddlePoseMap.StraddleMapping.identity())) {
            IplRapierNatives.routeRopePoint(scene, id, index, 0L, 0, 0, 0, 0, 0, 0, 1);
            return;
        }
        long dest = IplScenes.sceneHandle(route.getLast().to());
        if (dest == 0) return;
        var mapping = composed(route);
        Vec3 shift = mapping.shift();
        Quaterniond rot = mapping.mapQuat(new Quaterniond());
        IplRapierNatives.routeRopePoint(scene, id, index, dest, shift.x, shift.y, shift.z, rot.x, rot.y, rot.z, rot.w);
    }

    private static IplStraddlePoseMap.StraddleMapping composed(List<Hop> route) {
        var mapping = route.getFirst().mapping();
        for (int i = 1; i < route.size(); i++) mapping = mapping.then(route.get(i).mapping());
        return mapping;
    }

    private static Vec3 point(double[] flat, int index) {
        return new Vec3(flat[3 * index], flat[3 * index + 1], flat[3 * index + 2]);
    }
}
