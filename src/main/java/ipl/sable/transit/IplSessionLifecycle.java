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

/**
 * Tick-tail completion of an already shared group context. Entry/holding is prepared at
 * every physics boundary by {@link IplGroupTransitContext}; per-link labels stay independent.
 * No timeout, velocity forecast, or occupied-voxel bridge heuristic owns the group lifetime.
 *
 * <p>A U-shaped hole and the air between two joint links can still span a finite aperture.
 * Until the group's envelope clears every live rectangle, its material history stays split.
 * Afterwards all connected members select ONE equivalent terminal frame, including members
 * that never touched the rectangle themselves.
 */
final class IplSessionLifecycle {
    private IplSessionLifecycle() {}

    private static final class Member {
        final ServerSubLevel ship;
        final Body body;
        final IplMaterialSnapshot material;
        final Map<String, Integer> nodes = new HashMap<>();

        Member(ServerSubLevel ship) {
            this.ship = ship;
            body = IplPieceTracker.tracked(ship);
            material = new IplMaterialSnapshot(ship.getPlot(), body.map);
            nodes.put("", IplPieceMap.NATIVE);
            for (int node : body.liveNodes())
                if (node != IplPieceMap.NATIVE) nodes.put(body.keys[node].path(), node);
        }

        int node(String path) {
            int node = nodes.getOrDefault(path, -1);
            return node > 0 && body.keys[node] == null ? -1 : node;
        }
    }

    /** All native poses share a chart. A common portal prefix cancels in this reference frame. */
    private static final class Geometry {
        final Pose3d reference;
        final Bounds envelope;
        final Map<ServerLevel, Bounds> ownedEnvelopes = new HashMap<>();

        Geometry(List<Member> members) {
            reference = new Pose3d(members.getFirst().ship.logicalPose());
            Bounds bounds = null;
            for (Member m : members)
                bounds = IplTransitSettlement.union(bounds, m.material.boundsIn(m.ship.logicalPose(), reference));
            envelope = bounds;
        }

        IplPieceMap.Aperture aperture(IplTransitPath path) {
            return apertureFor(path, reference);
        }

        static IplPieceMap.Aperture apertureFor(IplTransitPath path, Pose3dc pose) {
            StraddleMapping parent = StraddleMapping.identity();
            for (int i = 0; i + 1 < path.portals().size(); i++)
                parent = parent.then(StraddleMapping.of(path.portals().get(i)));
            return IplPieceMap.Aperture.of(path.portals().getLast()).toPlot(parent.mapPose(pose));
        }

        /** Some member's material of this path's parent or subtree lies in its aperture slab. */
        boolean occupied(List<Member> members, IplTransitPath path) {
            for (Member m : members) {
                int node = m.node(path.orderKey());
                if (node <= 0) continue;
                int parent = m.body.parent[node];
                IplPieceMap.Aperture a = apertureFor(path, m.ship.logicalPose());
                if (m.material.occupies(l -> l == parent || m.body.inSubtree(l, node), a, SLAB_DEPTH, SLAB_MARGIN))
                    return true;
            }
            return false;
        }

        boolean front(IplTransitPath path) { return IplTransitSettlement.inFront(envelope, aperture(path)); }
        boolean behind(IplTransitPath path) { return IplTransitSettlement.behind(envelope, aperture(path)); }
        boolean intersects(IplTransitPath path) {
            if (IplTransitSettlement.intersects(envelope, aperture(path))) return true;
            // A wholly completed root must not be held by its own old source/image offset.
            // The extra chart-space guard protects undischarged mixed/deep group histories.
            if (throughRoot(path)) return false;
            Portal portal = path.portals().getLast();
            return IplTransitSettlement.intersects(ownedEnvelopes.get(portal.level()), IplPieceMap.Aperture.of(portal));
        }

        /**
         * Native coordinates alone cannot prove a flexible group's completion when its links
         * occupy DIFFERENT paths. Check their actually owned material in each real chart too.
         * Keep this envelope separate from the common-prefix envelope: unioning source and
         * destination copies would keep an ordinary completed portal crossing open forever.
         */
        boolean captureOwned(List<Member> members) {
            Pose3d world = new Pose3d();
            for (Member m : members) {
                for (int node : m.body.liveNodes()) {
                    if (m.material.counts[node] == 0) continue;
                    ServerLevel chart = m.ship.getLevel();
                    Pose3dc pose = m.ship.logicalPose();
                    if (node != IplPieceMap.NATIVE) {
                        chart = IplAtlasStraddleSession.sessionDest(m.body.keys[node]);
                        var mapping = IplAtlasStraddleSession.sessionMapping(m.body.keys[node]);
                        if (chart == null || mapping == null) return false;
                        pose = mapping.mapPose(pose);
                    }
                    Bounds bounds = m.material.boundsIn(node, pose, world);
                    ownedEnvelopes.merge(chart, bounds, IplTransitSettlement::union);
                }
            }
            return true;
        }

        boolean throughRoot(IplTransitPath path) {
            return IplTransitSettlement.behind(envelope,
                IplPieceMap.Aperture.of(path.portals().getFirst()).toPlot(reference));
        }
    }

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

    /** False means context preparation failed: the controller must not authorize a solo flip. */
    static boolean settle(List<ServerSubLevel> ships, int tick) {
        return IplStraddleSessionSync.batchResult(() -> settleGroup(ships, tick));
    }

    /**
     * A unit voxel can touch a plane only if its centre is within sqrt(3)/2 of it, in any
     * orientation; a connected body cut by a portal therefore always has such a voxel inside
     * the (equally grown) rectangle. Exact geometry, not a tuning distance.
     */
    private static final double SLAB_DEPTH = Math.sqrt(3.0) / 2.0 + 1.0e-6;
    private static final double SLAB_MARGIN = SLAB_DEPTH;

    private static boolean settleGroup(List<ServerSubLevel> ships, int tick) {
        IplGroupTransitContext.Context context = IplGroupTransitContext.prepare(ships, tick);
        if (context == null) return false;
        if (context.paths().isEmpty()) return true;
        List<Member> members = new ArrayList<>(ships.size());
        for (ServerSubLevel ship : ships) members.add(new Member(ship));
        Geometry geometry = new Geometry(members);
        repairDirections(members, geometry, context.paths());
        rejoinDetached(members, context.paths());
        if (!geometry.captureOwned(members)) return false;
        boolean split = materialFrames(members) > 1;
        retireUnusedPaths(members, geometry, context.paths(), split);
        for (Member m : members) IplPieceTracker.repaired(m.body, m.ship, tick);
        // Every prefix matters, including an empty path held for a different member/descendant.
        for (IplTransitPath path : context.paths()) {
            if (members.getFirst().node(path.orderKey()) > 0 && holds(members, geometry, path, split)) return true;
        }
        IplTransitPath chosen = chooseFrame(members, geometry, ships.getFirst().getLevel());
        return chosen != null && commit(members, chosen, tick);
    }

    /**
     * Only group-wide geometry proves a direction. Direct material can return without folding
     * a deeper valid descendant. A forward repair requires one used child across ALL members,
     * not merely the absence of a competing label on the particular link being repaired.
     */
    private static void repairDirections(List<Member> members, Geometry geometry, List<IplTransitPath> paths) {
        for (int i = paths.size() - 1; i >= 0; i--) {
            IplTransitPath path = paths.get(i);
            if (!geometry.front(path)) continue;
            for (Member m : members) {
                int node = m.node(path.orderKey());
                if (node > 0) m.material.move(node, m.body.parent[node]);
            }
        }
        for (IplTransitPath path : paths) {
            if (subtreeMaterial(members, path.orderKey()) == 0 || !geometry.behind(path)) continue;
            boolean sibling = false;
            for (IplTransitPath other : paths) {
                if (!other.orderKey().equals(path.orderKey()) && other.parentKey().equals(path.parentKey())
                    && subtreeMaterial(members, other.orderKey()) > 0) {
                    sibling = true;
                    break;
                }
            }
            if (sibling) continue;
            for (Member m : members) {
                int node = m.node(path.orderKey());
                if (node > 0) m.material.move(m.body.parent[node], node);
            }
        }
    }

    /**
     * A single body is really cut by a portal only through its aperture. When its parent-side
     * and passed material meet only around the frame (a ring spun around a two-sided portal,
     * material that went back past the frame edge), no block of it lies in the aperture slab:
     * the split is a labelling artefact, and the minority side rejoins the majority frame.
     * Cross-member splits are different -- joints, not material, connect them.
     */
    private static void rejoinDetached(List<Member> members, List<IplTransitPath> paths) {
        for (int i = paths.size() - 1; i >= 0; i--) {
            IplTransitPath path = paths.get(i);
            for (Member m : members) {
                int node = m.node(path.orderKey());
                if (node <= 0) continue;
                int parent = m.body.parent[node];
                long passed = 0;
                for (int l = 0; l < m.material.counts.length; l++)
                    if (m.body.inSubtree(l, node)) passed += m.material.counts[l];
                long kept = m.material.counts[parent];
                if (passed == 0 || kept == 0) continue;
                if (subtreeOccupied(m, path, paths)) continue;
                if (kept >= passed) {
                    for (int l = 0; l < m.material.counts.length; l++)
                        if (l != parent && m.body.inSubtree(l, node)) m.material.move(l, parent);
                } else {
                    m.material.move(parent, node);
                }
            }
        }
    }

    /** The path's own aperture or one below it holds this member's material right now. */
    private static boolean subtreeOccupied(Member m, IplTransitPath path, List<IplTransitPath> paths) {
        String prefix = path.orderKey() + ">";
        for (IplTransitPath p : paths) {
            if (!p.orderKey().equals(path.orderKey()) && !p.orderKey().startsWith(prefix)) continue;
            int node = m.node(p.orderKey());
            if (node <= 0) continue;
            int parent = m.body.parent[node];
            IplPieceMap.Aperture a = Geometry.apertureFor(p, m.ship.logicalPose());
            if (m.material.occupies(l -> l == parent || m.body.inSubtree(l, node), a, SLAB_DEPTH, SLAB_MARGIN))
                return true;
        }
        return false;
    }

    /** Distinct piece paths that own material anywhere in the group. */
    private static int materialFrames(List<Member> members) {
        Set<String> frames = new HashSet<>();
        for (Member m : members)
            for (int node : m.body.liveNodes())
                if (m.material.counts[node] > 0)
                    frames.add(node == IplPieceMap.NATIVE ? "" : m.body.keys[node].path());
        return frames.size();
    }

    /**
     * A path stays open while material is in its aperture, or -- for a group whose members
     * still live in different frames -- while the group envelope spans the rectangle.
     */
    private static boolean holds(List<Member> members, Geometry geometry, IplTransitPath path, boolean split) {
        return geometry.occupied(members, path) || split && geometry.intersects(path);
    }

    private static long subtreeMaterial(List<Member> members, String path) {
        long count = 0;
        for (Member m : members) {
            int root = m.node(path);
            if (root < 0) continue;
            for (int node = 0; node < m.material.counts.length; node++)
                if (m.material.counts[node] > 0 && m.body.inSubtree(node, root)) count += m.material.counts[node];
        }
        return count;
    }

    /** A locally empty link cannot release a path still owned by any mate or geometric gap. */
    private static void retireUnusedPaths(List<Member> members, Geometry geometry, List<IplTransitPath> paths,
                                          boolean split) {
        Set<String> keep = new HashSet<>();
        for (IplTransitPath path : paths) {
            if (subtreeMaterial(members, path.orderKey()) == 0 && !holds(members, geometry, path, split)) continue;
            String key = path.orderKey();
            while (!key.isEmpty()) {
                keep.add(key);
                int cut = key.lastIndexOf('>');
                key = cut < 0 ? "" : key.substring(0, cut);
            }
        }
        for (int i = paths.size() - 1; i >= 0; i--) {
            String key = paths.get(i).orderKey();
            if (keep.contains(key)) continue;
            for (Member m : members) {
                int node = m.node(key);
                if (node > 0) IplPieceTracker.endSubtree(m.body, m.ship.getLevel().getServer(), node, "group-unused");
            }
        }
    }

    @Nullable
    private static IplTransitPath chooseFrame(List<Member> members, Geometry geometry, ServerLevel source) {
        List<Frame> frames = new ArrayList<>();
        Frame nativeFrame = new Frame(new IplTransitPath(List.of(), source, StraddleMapping.identity()));
        frames.add(nativeFrame);
        for (Member m : members) {
            nativeFrame.material += m.material.counts[IplPieceMap.NATIVE];
            for (int node : m.body.liveNodes()) {
                if (node == IplPieceMap.NATIVE || m.material.counts[node] == 0) continue;
                IplTransitPath path = IplTransitPath.from(m.ship, m.body, node);
                if (path == null) return null;
                Frame equivalent = null;
                for (Frame frame : frames) if (frame.equivalent(path)) { equivalent = frame; break; }
                if (equivalent == null) { equivalent = new Frame(path); frames.add(equivalent); }
                equivalent.add(path, m.material.counts[node], geometry.throughRoot(path));
            }
        }
        frames.subList(1, frames.size()).sort(Comparator.comparing(f -> f.path.orderKey()));
        List<IplTransitSettlement.Vote> votes = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            votes.add(new IplTransitSettlement.Vote(i, f.material, f.through));
        }
        return frames.get(IplTransitSettlement.chooseFrame(nativeFrame.material, votes)).path;
    }

    private record Opened(Body body, ServerSubLevel ship, int node) {}
    private record Prepared(Member member, Body body, int target) {}

    /** Relabel only after every member's complete chosen path has been prepared successfully. */
    private static boolean commit(List<Member> members, IplTransitPath chosen, int tick) {
        for (Member m : members) if (!chosen.canOpenFor(m.ship)) return false;
        List<Opened> opened = new ArrayList<>();
        List<Prepared> prepared = new ArrayList<>(members.size());
        for (Member m : members) {
            Body body = IplPieceTracker.trackForContext(m.ship);
            int node = IplPieceMap.NATIVE;
            for (Portal portal : chosen.portals()) {
                int child = body.childOf(node, portal.getUUID());
                if (child < 0) {
                    child = IplPieceTracker.openSession(body, m.ship, node, portal);
                    if (child < 0) {
                        for (int i = opened.size() - 1; i >= 0; i--) {
                            Opened o = opened.get(i);
                            if (o.body.keys[o.node] != null)
                                IplPieceTracker.endSubtree(o.body, o.ship.getLevel().getServer(), o.node, "settlement-rollback");
                        }
                        return false;
                    }
                    opened.add(new Opened(body, m.ship, child));
                }
                node = child;
            }
            prepared.add(new Prepared(m, body, node));
        }
        for (Prepared p : prepared) {
            Body body = p.body;
            int target = p.target;
            p.member.material.assign(target, body.map);
            boolean[] keep = new boolean[IplPieceMap.MAX_PIECE + 1];
            for (int at = target; at > 0; at = body.parent[at]) keep[at] = true;
            List<Integer> nodes = body.liveNodes();
            for (int i = nodes.size() - 1; i > 0; i--) {
                int node = nodes.get(i);
                if (!keep[node] && body.keys[node] != null)
                    IplPieceTracker.endSubtree(body, p.member.ship.getLevel().getServer(), node, "settled-clear");
            }
            // Air must not resurrect a freed label on a later placement.
            body.map.relabel(label -> label == target ? target : IplPieceMap.NATIVE);
            if (target == IplPieceMap.NATIVE) {
                if (!body.map.isEmpty()) body.map.clear();
                body.fullSync = true;
            }
            IplPieceTracker.settled(body, p.member.ship, tick, target, p.member.material.total() == 0);
        }
        return true;
    }
}
