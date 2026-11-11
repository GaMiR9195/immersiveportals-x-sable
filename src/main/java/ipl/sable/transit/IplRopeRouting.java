package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import ipl.sable.transit.IplPieceTracker.Body;
import ipl.sable.transit.IplStraddlePoseMap.StraddleMapping;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Portal routing of free rope points (native ABI 8), derived from the sub-levels a rope hangs on.
 *
 * <p>A rope point is a free native body of its strand's chart. While a sub-level the rope is
 * attached to straddles a portal, the points next to its passed part lie behind the portal plane
 * in the SOURCE level, inside whatever is behind the portal, and would hold the ship on phantom
 * ground. Their colliders must live where the passed part lives.
 *
 * <p>Nothing is remembered per point. Every physics sample, each rope attached to a body with
 * sessions is walked from that attachment: the piece of the attached block gives the frame of
 * the end point, a segment that passes one of the body's apertures moves the walk into that
 * child frame, and a segment that passes back through the aperture it came from returns to the
 * parent frame. Apertures are finite rectangles, so a rope that bends around the frame stays
 * where it is. Only the frames the body itself is split into are possible. A point collides in
 * its frame's chart through the composed isometry, exactly like a body image; once no attached
 * body has sessions, every point collides natively again. The point bodies, their joints and the
 * owning mod's logic and rendering are untouched.
 */
public final class IplRopeRouting {
    private static final String NATIVE = "";

    /** Per level and rope: the frame each point was last routed to; {@code null} = unknown. */
    private static final Map<ServerLevel, Map<Long, String[]>> ROUTED = new WeakHashMap<>();

    private IplRopeRouting() {}

    public static boolean supported() {
        return IplRapierNatives.abiLevel() >= IplRapierNatives.ROPE_ROUTE_ABI;
    }

    /** Once per physics sample of a level, after the step. */
    public static void sample(ServerLevel level) {
        if (!supported()) return;
        long scene = IplScenes.sceneHandle(level);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Map<Long, String[]> routed = ROUTED.computeIfAbsent(level, ignored -> new HashMap<>());
        if (scene == 0 || container == null) {
            routed.clear();
            return;
        }
        Map<Long, Anchor[]> ropes = anchoredRopes(scene, container);
        if (ropes.isEmpty() && routed.isEmpty()) return;
        for (var rope : ropes.entrySet()) route(level, scene, rope.getKey(), rope.getValue(), routed);
        routed.keySet().removeIf(rope -> {
            if (ropes.containsKey(rope)) return false;
            release(scene, rope, routed.get(rope));
            return true;
        });
    }

    /** Ropes moved with a group: every point is routed again at the next sample. */
    public static void forget(ServerLevel level, Set<Long> ropes) {
        Map<Long, String[]> routed = ROUTED.get(level);
        if (routed == null) return;
        for (long rope : ropes) {
            String[] points = routed.get(rope);
            if (points != null) Arrays.fill(points, null);
        }
    }

    public static void clearAll() {
        ROUTED.clear();
    }

    // ------------------------------------------------------------------ anchors

    /** One rope end held by a body with sessions; {@code start} is point 0, otherwise the last point. */
    private record Anchor(ServerSubLevel ship, Body body) {}

    private static Map<Long, Anchor[]> anchoredRopes(long scene, ServerSubLevelContainer container) {
        Map<Long, Anchor[]> ropes = new LinkedHashMap<>();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved()) continue;
            Body body = IplPieceTracker.tracked(ship);
            if (body == null || !body.hasSessions()) continue;
            int id = Rapier3D.getID(ship);
            if (id < 0) continue;
            for (long packed : IplRapierNatives.ropesAttachedToSableBody(scene, id)) {
                ropes.computeIfAbsent(packed >> 1, ignored -> new Anchor[2])[(int) (packed & 1L)] = new Anchor(ship, body);
            }
        }
        return ropes;
    }

    // ------------------------------------------------------------------ walk

    private static void route(ServerLevel level, long scene, long rope, Anchor[] ends, Map<Long, String[]> routed) {
        double[] flat = IplRapierNatives.ropePointPositions(scene, rope);
        int count = flat.length / 3;
        if (count == 0) return;
        String[] previous = routed.get(rope);
        if (previous == null || previous.length != count) routed.put(rope, previous = new String[count]);

        int[] frames = new int[count];
        Body[] owners = new Body[count];
        Anchor start = ends[0], end = ends[1];
        int split = start != null && end != null ? count / 2 : start != null ? count : 0;
        if (start != null) walk(start, flat, 0, split, 1, frames, owners);
        if (end != null) walk(end, flat, count - 1, split - 1, -1, frames, owners);

        for (int i = 0; i < count; i++) {
            Body owner = owners[i];
            int node = owner == null ? IplPieceMap.NATIVE : frames[i];
            String frame = node == IplPieceMap.NATIVE ? NATIVE : owner.keys[node].path();
            if (frame.equals(previous[i])) continue;
            if (apply(level, scene, rope, i, node == IplPieceMap.NATIVE ? null : owner.keys[node])) previous[i] = frame;
        }
    }

    /** Assigns frames to points {@code from} (inclusive) towards {@code to} (exclusive). */
    private static void walk(Anchor anchor, double[] flat, int from, int to, int step, int[] frames, Body[] owners) {
        Body body = anchor.body();
        int node = attachedNode(anchor, point(flat, from));
        for (int i = from; i != to; i += step) {
            frames[i] = node;
            owners[i] = body;
            int next = i + step;
            if (next < 0 || next >= frames.length) break;
            node = nextFrame(body, node, point(flat, i), point(flat, next));
        }
    }

    /** The piece of the block the rope end is attached to. */
    private static int attachedNode(Anchor anchor, Vec3 end) {
        Vector3d plot = anchor.ship().logicalPose().transformPositionInverse(new Vector3d(end.x, end.y, end.z));
        int label = anchor.body().map.label((int) Math.floor(plot.x), (int) Math.floor(plot.y), (int) Math.floor(plot.z));
        return label == IplPieceMap.NATIVE || anchor.body().keys[label] != null ? label : IplPieceMap.NATIVE;
    }

    /** Frame after segment {@code a -> b} (raw strand-chart coordinates) left frame {@code node}. */
    private static int nextFrame(Body body, int node, Vec3 a, Vec3 b) {
        if (node != IplPieceMap.NATIVE) {
            int parent = body.parent[node];
            Portal entered = IplAtlasStraddleSession.sessionPortal(body.keys[node]);
            StraddleMapping outer = mappingOf(body, parent);
            if (entered != null && outer != null
                && passes(IplPieceMap.Aperture.of(entered), outer.mapPoint(a), outer.mapPoint(b), false)) {
                return parent;
            }
        }
        StraddleMapping here = mappingOf(body, node);
        if (here == null) return node;
        Vec3 ha = here.mapPoint(a), hb = here.mapPoint(b);
        for (int child : body.liveNodes()) {
            if (child == IplPieceMap.NATIVE || body.parent[child] != node) continue;
            Portal portal = IplAtlasStraddleSession.sessionPortal(body.keys[child]);
            if (portal != null && passes(IplPieceMap.Aperture.of(portal), ha, hb, true)) return child;
        }
        return node;
    }

    private static StraddleMapping mappingOf(Body body, int node) {
        return node == IplPieceMap.NATIVE ? StraddleMapping.identity()
            : IplAtlasStraddleSession.sessionMapping(body.keys[node]);
    }

    /** Segment {@code a -> b} passes the finite rectangle front-to-back ({@code forward}) or back. */
    private static boolean passes(IplPieceMap.Aperture aperture, Vec3 a, Vec3 b, boolean forward) {
        double sa = aperture.side(a.x, a.y, a.z), sb = aperture.side(b.x, b.y, b.z);
        if (forward ? !(sa >= 0.0 && sb < 0.0) : !(sa < 0.0 && sb >= 0.0)) return false;
        double t = sa / (sa - sb);
        double x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t, z = a.z + (b.z - a.z) * t;
        return Math.abs(aperture.u(x, y, z)) <= aperture.halfW() && Math.abs(aperture.v(x, y, z)) <= aperture.halfH();
    }

    // ------------------------------------------------------------------ natives

    /** Routes one point into {@code key}'s frame, or back to its own chart for {@code null}. */
    private static boolean apply(ServerLevel level, long scene, long rope, int index, StraddleKey key) {
        StraddleMapping mapping = key == null ? null : IplAtlasStraddleSession.sessionMapping(key);
        ServerLevel dest = key == null ? null : IplAtlasStraddleSession.sessionDest(key);
        if (mapping == null || dest == null
            || dest == level && mapping.sameAs(StraddleMapping.identity())) {
            return IplRapierNatives.routeRopePoint(scene, rope, index, 0L, 0, 0, 0, 0, 0, 0, 1);
        }
        long destScene = IplScenes.sceneHandle(dest);
        if (destScene == 0) return false;
        Vec3 shift = mapping.shift();
        Quaterniond rotation = mapping.mapQuat(new Quaterniond());
        return IplRapierNatives.routeRopePoint(scene, rope, index, destScene,
            shift.x, shift.y, shift.z, rotation.x, rotation.y, rotation.z, rotation.w);
    }

    /** A rope no body holds through a portal any more collides natively again. */
    private static void release(long scene, long rope, String[] points) {
        if (points == null) return;
        for (int i = 0; i < points.length; i++) {
            if (Objects.equals(points[i], NATIVE)) continue;
            IplRapierNatives.routeRopePoint(scene, rope, i, 0L, 0, 0, 0, 0, 0, 0, 1);
        }
    }

    private static Vec3 point(double[] flat, int index) {
        return new Vec3(flat[3 * index], flat[3 * index + 1], flat[3 * index + 2]);
    }
}
