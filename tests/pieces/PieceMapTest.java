package ipl.sable.transit;

import net.minecraft.core.SectionPos;
import java.util.Random;

/** Headless regressions for the exact finite-aperture material rule. */
public final class PieceMapTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
    private static IplPieceMap.Aperture aperture(double x) {
        return new IplPieceMap.Aperture(x, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1);
    }
    private static IplPieceMap.Edge edge(int parent, int child, double before, double now) {
        return new IplPieceMap.Edge(parent, child, aperture(now), aperture(before));
    }
    public static void main(String[] args) {
        IplPieceMap map = new IplPieceMap();
        IplPieceMap.Edge[] split = {edge(0, 1, 0.2, 0.2)};
        check(map.classify(split, 0.1, 0.5, 0.5) == 1, "surface behind plane of a native split voxel");
        check(map.classify(split, 0.9, 0.5, 0.5) == 0, "native surface in front");
        check(map.classify(new IplPieceMap.Edge[]{edge(0, 1, 1, 1)}, 0.9, 0.5, 0.5) == 0,
            "unpassed voxel behind a finite plane is not stolen");
        map.setLabel(0, 0, 3, 1);
        check(map.classify(split, 0.9, 0.5, 3.5) == 1, "transferred material outside aperture stays transferred");
        map.setLabel(0, 0, 0, 1);
        check(map.classify(new IplPieceMap.Edge[]{edge(0, 1, -20, -20)}, 0.5, 0.5, 0.5) == 1,
            "transferred material on old entry side stays transferred without a reverse crossing");
        map.setLabel(0, 0, 0, 0);
        for (double speed : new double[]{0.4, 1, 32, 33, 64, 1000, 1000000}) {
            IplPieceMap.Edge[] edges = {edge(0, 1, 0, speed)};
            int expected = speed > 0.5 ? 1 : 0;
            check(IplPieceMap.voxelLabel(edges, 0, 0, 0, 0) == expected, "observed speed " + speed);
        }
        check(IplPieceMap.voxelLabel(new IplPieceMap.Edge[]{edge(0, 1, 0, 1000)}, 0, 3, 0, 0) == 0,
            "huge speed does not turn finite aperture into an infinite plane");
        IplPieceMap.Edge[] enter = {edge(0, 1, 0, 1)};
        check(IplPieceMap.voxelLabel(enter, 0, 0, 0, 0) == 1, "one-block insertion");
        map.setLabel(0, 0, 0, 1);
        check(map.classify(new IplPieceMap.Edge[]{edge(0, 1, 1, 0)}, 0.5, 0.5, 0.5) == 0,
            "one-block immediate withdrawal");
        map.setLabel(0, 0, 0, 0);
        IplPieceMap.Edge opposite = new IplPieceMap.Edge(0, 2,
            new IplPieceMap.Aperture(0.2, 0, 0, -1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1),
            new IplPieceMap.Aperture(0.2, 0, 0, -1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1));
        check(map.classify(new IplPieceMap.Edge[]{opposite, split[0]}, 0.9, 0.5, 0.5) == 0,
            "U: opposite face cannot steal front arm");
        // First edge is deliberately listed last; nested crossings must use time order.
        IplPieceMap.Edge first = edge(0, 1, 0, 4);
        IplPieceMap.Edge nested = edge(1, 2, -2, 2);
        check(IplPieceMap.voxelLabel(new IplPieceMap.Edge[]{nested, first}, 0, 0, 0, 0) == 2,
            "nested crossings are chronological, not node order");
        check(IplPieceMap.voxelLabel(new IplPieceMap.Edge[]{edge(0, 2, 0, 2), first}, 0, 0, 0, 0) == 1,
            "earliest sibling crossing wins");
        Random random = new Random(0x060);
        for (int i = 0; i < 5000; i++) {
            double before = -1 - random.nextDouble() * 1000, now = 2 + random.nextDouble() * 1000;
            IplPieceMap.Edge[] edges = {edge(0, 1, before, now)};
            check(IplPieceMap.voxelLabel(edges, 0, 0, 0, 0) == 1, "random forward crossing");
            check(IplPieceMap.voxelLabel(edges, 0, 2, 0, 0) == 0, "random finite miss");
            check(IplPieceMap.voxelLabel(new IplPieceMap.Edge[]{edge(0, 1, now, before)}, 0, 0, 0, 1) == 0,
                "random reverse crossing");
        }
        map.setEdges(split);
        map.setLabel(-5, 2, 0, 1);
        check(map.has(1), "piece count");
        long key = SectionPos.asLong(-1, 0, 0);
        check(IplPieceMap.contains(map.present(key), 1), "present set");
        IplPieceMap copy = new IplPieceMap();
        copy.decode(map.encode(null));
        check(copy.label(-5, 2, 0) == 1 && copy.edges().length == 1, "full codec includes reference aperture");
        check(copy.edges()[0].aperture.equals(split[0].aperture), "exact aperture codec roundtrip");
        map.drainDirty(); map.relabel(l -> 0);
        copy.decode(map.encode(map.drainDirty()));
        check(copy.isEmpty() && !copy.has(1), "delta removes empty sections");
        map.setLabel(5, 5, 5, 3); map.translate(2, -1);
        check(map.label(37, 5, -11) == 3 && map.label(5, 5, 5) == 0, "plot translation");
        check(map.edges()[0].aperture.ox() == 32.2 && map.edges()[0].reference.oz() == -16,
            "plot translation moves both aperture and reference geometry");
        IplPieceMap geometryOnly = new IplPieceMap();
        geometryOnly.setEdges(split); geometryOnly.translate(-1, 2);
        check(geometryOnly.isEmpty() && geometryOnly.edges()[0].reference.ox() == -15.8,
            "geometry translates even before a voxel centre has crossed");
        geometryOnly.clear();
        check(geometryOnly.edges().length == 0, "clear drops stale aperture geometry");
        check(IplApertureSweep.boxMayIntersect(aperture(-1000), aperture(1000), 0, 0, 0, 16, 1),
            "section broad phase includes the entire high-speed observed sweep");
        check(!IplApertureSweep.boxMayIntersect(aperture(-1000), aperture(1000), 0, 16, 0, 16, 1),
            "section broad phase remains finite laterally at high speed");
        check(IplPieceMap.voxelLabel(new IplPieceMap.Edge[]{edge(0, 1, 0, 1), edge(1, 2, 0, 1)},
            0, 0, 0, 0) == 1, "coincident crossings cannot bounce material twice at one instant");
        check(IplPieceMap.zoneOfVoxel(new IplPieceMap.Edge[]{edge(0, 1, 0, 1000)}, 0, 0, 0, 0) < 0,
            "large observed motion transports whole blocks, not a predictive split slab");
        System.out.println("PASS: " + checks + " piece-map assertions");
    }
}
