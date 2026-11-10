package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.transit.IplPieceTracker.Body;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared path ownership, starting at the FIRST link's observed entrance, not at settlement.
 *
 * <p>The union of live paths is prepared on every connected member. A member's piece ids,
 * voxel labels and sweep observations remain its own; an empty group image does not receive
 * another link's material. Individual sampling cannot retire these paths. Tick-tail lifecycle
 * releases an unused path only for the whole component, or hands the common root to native.
 *
 * <p>Membership is rebuilt at each physics boundary. Mirrored path ownership survives between
 * boundaries in the bodies' trees; no permanent group-id cache can retain a broken spring or
 * prevent two formerly separate components from joining.
 */
final class IplGroupTransitContext {
    private IplGroupTransitContext() {}

    record Context(List<IplTransitPath> paths) {}
    private record Opened(Body body, ServerSubLevel ship, int node) {}

    /** Atomic session advertisement, including a failed prepare's rollback. No labels move here. */
    @Nullable
    static Context prepare(List<ServerSubLevel> ships, int tick) {
        return IplStraddleSessionSync.batchResult(() -> preparePaths(ships, tick));
    }

    @Nullable
    private static Context preparePaths(List<ServerSubLevel> ships, int tick) {
        if (ships.isEmpty()) return new Context(List.of());
        var source = ships.getFirst().getLevel();
        Map<String, IplTransitPath> union = new LinkedHashMap<>();
        for (ServerSubLevel ship : ships) {
            if (ship.isRemoved() || ship.getLevel() != source) return null;
            Body body = IplPieceTracker.tracked(ship);
            if (body == null) continue;
            for (int node : body.liveNodes()) {
                if (node == IplPieceMap.NATIVE) continue;
                IplTransitPath path = IplTransitPath.from(ship, body, node);
                if (path == null) return null;
                union.putIfAbsent(path.orderKey(), path);
            }
        }
        List<IplTransitPath> paths = new ArrayList<>(union.values());
        paths.sort(Comparator.comparingInt(IplTransitPath::depth).thenComparing(IplTransitPath::orderKey));
        if (paths.isEmpty()) return new Context(List.of());

        // Check every member and capacity before allocating even the first new image.
        Map<Body, Map<String, Integer>> nodes = new LinkedHashMap<>();
        for (ServerSubLevel ship : ships) {
            for (IplTransitPath path : paths) if (!path.canOpenFor(ship)) return null;
            Body body = IplPieceTracker.trackForContext(ship);
            Map<String, Integer> own = new HashMap<>();
            own.put("", IplPieceMap.NATIVE);
            for (int node : body.liveNodes())
                if (node != IplPieceMap.NATIVE) own.put(body.keys[node].path(), node);
            long missing = paths.stream().filter(path -> !own.containsKey(path.orderKey())).count();
            if (own.size() - 1 + missing > IplPieceMap.MAX_PIECE) return null;
            nodes.put(body, own);
        }
        List<Opened> opened = new ArrayList<>();
        for (ServerSubLevel ship : ships) {
            Body body = IplPieceTracker.tracked(ship);
            Map<String, Integer> own = nodes.get(body);
            for (IplTransitPath path : paths) {
                String key = path.orderKey();
                if (own.containsKey(key)) continue;
                Integer parent = own.get(path.parentKey());
                Portal portal = path.portals().getLast();
                int node = parent == null ? -1 : IplPieceTracker.openSession(body, ship, parent, portal);
                if (node < 0) {
                    for (int i = opened.size() - 1; i >= 0; i--) {
                        Opened o = opened.get(i);
                        if (o.body.keys[o.node] != null)
                            IplPieceTracker.endSubtree(o.body, o.ship.getLevel().getServer(),
                                o.node, "group-prepare-rollback");
                    }
                    return null;
                }
                opened.add(new Opened(body, ship, node));
                own.put(key, node);
            }
        }
        // Do not reset existing watches: they still describe this member's observed motion.
        for (ServerSubLevel ship : ships)
            IplPieceTracker.contextReady(IplPieceTracker.tracked(ship), ship, tick);
        return new Context(List.copyOf(paths));
    }
}
