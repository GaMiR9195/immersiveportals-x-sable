package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.transit.IplPieceTracker.Body;
import ipl.sable.transit.IplStraddlePoseMap.StraddleMapping;
import ipl.sable.transit.IplTransitSettlement.Bounds;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Tick-tail settlement of ONE body's sessions. Labels are history; this decides when that
 * history is finished. No timeout, velocity forecast or occupied-voxel heuristic is involved:
 *
 * <ol>
 *   <li>a path whose aperture lies wholly in front of or behind the body proves where its
 *       material is ({@link #repairDirections});</li>
 *   <li>material split by a path but not cut by its aperture (a ring spun around a two-sided
 *       portal, blocks that went back past the frame edge) rejoins the larger side
 *       ({@link #rejoinDetached});</li>
 *   <li>a path without material and without anything in its aperture is retired;</li>
 *   <li>once no aperture holds anything, a body left in several frames is collected into the
 *       frame of most of its material ({@link #collect}).</li>
 * </ol>
 *
 * <p>Only the body's own material ever moves. Linked bodies (joints, springs, ropes) are never
 * pulled into another body's frame: each one crosses by itself, the passed ones wait on their
 * projections, and the whole group flips together ({@link IplTransitController}).
 */
final class IplSessionLifecycle {
    /** A unit voxel touches a plane only if its centre is within sqrt(3)/2 of it, in any orientation. */
    private static final double SLAB = Math.sqrt(3.0) / 2.0 + 1.0e-6;

    private IplSessionLifecycle() {}

    static void settle(ServerSubLevel ship, int tick) {
        Body body = IplPieceTracker.tracked(ship);
        if (body == null || !body.hasSessions()) return;
        IplStraddleSessionSync.batch(() -> {
            Settlement settlement = Settlement.of(ship, body);
            if (settlement != null) settlement.run(tick);
        });
    }

    private static final class Settlement {
        final ServerSubLevel ship;
        final Body body;
        final Pose3dc pose;
        final IplMaterialSnapshot material;
        /** Live paths, parents before children. */
        final List<IplTransitPath> paths;
        final Map<String, Integer> nodes = new HashMap<>();
        /** Material bounds in the plot frame: one portal prefix cancels for every path. */
        final Bounds envelope;
        /** Material actually owned in each real chart, for paths that already hold material. */
        final Map<ServerLevel, Bounds> owned = new HashMap<>();

        private Settlement(ServerSubLevel ship, Body body, List<IplTransitPath> paths) {
            this.ship = ship;
            this.body = body;
            this.pose = new Pose3d(ship.logicalPose());
            this.material = new IplMaterialSnapshot(ship.getPlot(), body.map);
            this.paths = paths;
            this.envelope = material.allBounds();
            nodes.put("", IplPieceMap.NATIVE);
            for (int node : body.liveNodes())
                if (node != IplPieceMap.NATIVE) nodes.put(body.keys[node].path(), node);
        }

        @Nullable
        static Settlement of(ServerSubLevel ship, Body body) {
            List<IplTransitPath> paths = new ArrayList<>();
            for (int node : body.liveNodes()) {
                if (node == IplPieceMap.NATIVE) continue;
                IplTransitPath path = IplTransitPath.from(ship, body, node);
                if (path == null) return null;
                paths.add(path);
            }
            if (paths.isEmpty()) return null;
            paths.sort(Comparator.comparingInt(IplTransitPath::depth).thenComparing(IplTransitPath::orderKey));
            return new Settlement(ship, body, paths);
        }

        void run(int tick) {
            repairDirections();
            rejoinDetached();
            if (!captureOwned()) return;
            boolean split = frames() > 1;
            retireUnused(split);
            IplPieceTracker.repaired(body, ship, tick);
            for (IplTransitPath path : paths) {
                if (node(path) > 0 && holds(path, split)) return;
            }
            collect(tick);
        }

        // -------------------------------------------------------------- geometry

        int node(IplTransitPath path) {
            int node = nodes.getOrDefault(path.orderKey(), -1);
            return node > 0 && body.keys[node] == null ? -1 : node;
        }

        /** The path's aperture in the plot frame of the body. */
        IplPieceMap.Aperture aperture(IplTransitPath path) {
            StraddleMapping parent = StraddleMapping.identity();
            for (int i = 0; i + 1 < path.portals().size(); i++)
                parent = parent.then(StraddleMapping.of(path.portals().get(i)));
            return IplPieceMap.Aperture.of(path.portals().getLast()).toPlot(parent.mapPose(pose));
        }

        IntPredicate edgeMaterial(int node) {
            int parent = body.parent[node];
            return label -> label == parent || body.inSubtree(label, node);
        }

        /** Material of this edge has a block in the aperture slab: the body is cut there. */
        boolean occupied(IplTransitPath path) {
            int node = node(path);
            return node > 0 && material.occupies(edgeMaterial(node), aperture(path), SLAB, SLAB);
        }

        boolean subtreeOccupied(IplTransitPath path) {
            String prefix = path.orderKey() + ">";
            for (IplTransitPath p : paths) {
                if (p == path || p.orderKey().startsWith(prefix)) {
                    if (occupied(p)) return true;
                }
            }
            return false;
        }

        boolean front(IplTransitPath path) { return IplTransitSettlement.inFront(envelope, aperture(path)); }

        boolean behind(IplTransitPath path) { return IplTransitSettlement.behind(envelope, aperture(path)); }

        /** The whole body went through the first portal of the path. */
        boolean throughRoot(IplTransitPath path) {
            return IplTransitSettlement.behind(envelope,
                IplPieceMap.Aperture.of(path.portals().getFirst()).toPlot(pose));
        }

        boolean intersects(IplTransitPath path) {
            if (IplTransitSettlement.intersects(envelope, aperture(path))) return true;
            if (throughRoot(path)) return false;
            Portal portal = path.portals().getLast();
            return IplTransitSettlement.intersects(owned.get(portal.level()), IplPieceMap.Aperture.of(portal));
        }

        /** A path stays open while it cuts material, or while the body is split and spans it. */
        boolean holds(IplTransitPath path, boolean split) {
            return occupied(path) || split && intersects(path);
        }

        boolean captureOwned() {
            Pose3d world = new Pose3d();
            for (int node : body.liveNodes()) {
                if (material.counts[node] == 0) continue;
                ServerLevel chart = ship.getLevel();
                Pose3dc at = pose;
                if (node != IplPieceMap.NATIVE) {
                    chart = IplAtlasStraddleSession.sessionDest(body.keys[node]);
                    StraddleMapping mapping = IplAtlasStraddleSession.sessionMapping(body.keys[node]);
                    if (chart == null || mapping == null) return false;
                    at = mapping.mapPose(pose);
                }
                owned.merge(chart, material.boundsIn(node, at, world), IplTransitSettlement::union);
            }
            return true;
        }

        // -------------------------------------------------------------- material

        long subtreeMaterial(IplTransitPath path) {
            int root = node(path);
            if (root < 0) return 0;
            long count = 0;
            for (int node = 0; node < material.counts.length; node++)
                if (material.counts[node] > 0 && body.inSubtree(node, root)) count += material.counts[node];
            return count;
        }

        int frames() {
            Set<String> frames = new HashSet<>();
            for (int node : body.liveNodes())
                if (material.counts[node] > 0)
                    frames.add(node == IplPieceMap.NATIVE ? "" : body.keys[node].path());
            return frames.size();
        }

        /** Geometry alone proves a direction: wholly in front returns, wholly behind passes. */
        void repairDirections() {
            for (int i = paths.size() - 1; i >= 0; i--) {
                IplTransitPath path = paths.get(i);
                int node = node(path);
                if (node > 0 && front(path)) material.move(node, body.parent[node]);
            }
            for (IplTransitPath path : paths) {
                int node = node(path);
                if (node <= 0 || subtreeMaterial(path) == 0 || !behind(path) || hasLoadedSibling(path)) continue;
                material.move(body.parent[node], node);
            }
        }

        boolean hasLoadedSibling(IplTransitPath path) {
            for (IplTransitPath other : paths) {
                if (other != path && other.parentKey().equals(path.parentKey()) && subtreeMaterial(other) > 0)
                    return true;
            }
            return false;
        }

        /** Two sides that meet only around the frame are one piece: the smaller side rejoins. */
        void rejoinDetached() {
            for (int i = paths.size() - 1; i >= 0; i--) {
                IplTransitPath path = paths.get(i);
                int node = node(path);
                if (node <= 0) continue;
                int parent = body.parent[node];
                long passed = 0;
                for (int l = 0; l < material.counts.length; l++)
                    if (body.inSubtree(l, node)) passed += material.counts[l];
                long kept = material.counts[parent];
                if (passed == 0 || kept == 0 || subtreeOccupied(path)) continue;
                if (kept >= passed) {
                    for (int l = 0; l < material.counts.length; l++)
                        if (l != parent && body.inSubtree(l, node)) material.move(l, parent);
                } else {
                    material.move(parent, node);
                }
            }
        }

        void retireUnused(boolean split) {
            Set<String> keep = new HashSet<>();
            for (IplTransitPath path : paths) {
                if (subtreeMaterial(path) == 0 && !holds(path, split)) continue;
                for (String key = path.orderKey(); !key.isEmpty(); ) {
                    keep.add(key);
                    int cut = key.lastIndexOf('>');
                    key = cut < 0 ? "" : key.substring(0, cut);
                }
            }
            for (int i = paths.size() - 1; i >= 0; i--) {
                IplTransitPath path = paths.get(i);
                int node = node(path);
                if (node > 0 && !keep.contains(path.orderKey()))
                    IplPieceTracker.endSubtree(body, ship.getLevel().getServer(), node, "unused");
            }
        }

        /** Nothing is in any aperture: the body's material goes to one frame. */
        void collect(int tick) {
            IplTransitPath chosen = chooseFrame();
            if (chosen == null) return;
            int target = chosen.depth() == 0 ? IplPieceMap.NATIVE : node(chosen);
            if (target < 0) return;
            material.assign(target, body.map);
            boolean[] keep = new boolean[IplPieceMap.MAX_PIECE + 1];
            for (int at = target; at > 0; at = body.parent[at]) keep[at] = true;
            List<Integer> live = body.liveNodes();
            for (int i = live.size() - 1; i > 0; i--) {
                int node = live.get(i);
                if (!keep[node] && body.keys[node] != null)
                    IplPieceTracker.endSubtree(body, ship.getLevel().getServer(), node, "settled");
            }
            // Air must not resurrect a freed label on a later placement.
            body.map.relabel(label -> label == target ? target : IplPieceMap.NATIVE);
            if (target == IplPieceMap.NATIVE) {
                if (!body.map.isEmpty()) body.map.clear();
                body.fullSync = true;
            }
            IplPieceTracker.settled(body, ship, tick);
        }

        @Nullable
        IplTransitPath chooseFrame() {
            List<Frame> frames = new ArrayList<>();
            Frame nativeFrame = new Frame(new IplTransitPath(List.of(), ship.getLevel(), StraddleMapping.identity()));
            nativeFrame.material = material.counts[IplPieceMap.NATIVE];
            frames.add(nativeFrame);
            for (int node : body.liveNodes()) {
                if (node == IplPieceMap.NATIVE || material.counts[node] == 0) continue;
                IplTransitPath path = IplTransitPath.from(ship, body, node);
                if (path == null) return null;
                Frame frame = frames.stream().filter(f -> f.equivalent(path)).findFirst().orElse(null);
                if (frame == null) frames.add(frame = new Frame(path));
                frame.add(path, material.counts[node], throughRoot(path));
            }
            frames.subList(1, frames.size()).sort(Comparator.comparing(f -> f.path.orderKey()));
            List<IplTransitSettlement.Vote> votes = new ArrayList<>(frames.size());
            for (int i = 0; i < frames.size(); i++) {
                Frame f = frames.get(i);
                votes.add(new IplTransitSettlement.Vote(i, f.material, f.through));
            }
            return frames.get(IplTransitSettlement.chooseFrame(nativeFrame.material, votes)).path;
        }
    }

    /** Equivalent paths (same destination and isometry) are one frame. */
    private static final class Frame {
        IplTransitPath path;
        long material;
        boolean through;

        Frame(IplTransitPath path) { this.path = path; }

        boolean equivalent(IplTransitPath other) {
            return path.destination() == other.destination() && path.mapping().sameAs(other.mapping());
        }

        void add(IplTransitPath candidate, long weight, boolean completedRoot) {
            material += weight;
            through |= completedRoot;
            if (candidate.depth() < path.depth()
                || candidate.depth() == path.depth() && candidate.orderKey().compareTo(path.orderKey()) < 0)
                path = candidate;
        }
    }
}
