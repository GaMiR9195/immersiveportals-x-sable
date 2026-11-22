package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.IntPredicate;

/**
 * Server side of contraptions on straddling bodies ({@link IplContraptionShape}): they open and
 * hold the sessions they reach, flip as part of their body (it waits until they are through too)
 * and give the natives the point their contacts are walked from.
 */
public final class IplContraptionMaterial {

    /** Native id of a kinematic contraption and the anchor last pushed for it. */
    private static final class NativeEntry {
        final long scene;
        final int id;
        double x = Double.NaN, y = Double.NaN, z = Double.NaN;

        NativeEntry(long scene, int id) {
            this.scene = scene;
            this.id = id;
        }
    }

    private static final Map<KinematicContraption, NativeEntry> NATIVE = new WeakHashMap<>();
    /** Shapes of one server tick; contraptions move once per tick. */
    private static final Map<UUID, List<IplContraptionShape>> SHAPES = new HashMap<>();
    private static int shapesTick = Integer.MIN_VALUE;

    private IplContraptionMaterial() {}

    static List<IplContraptionShape> shapes(ServerSubLevel ship) {
        if (!IplContraptions.CREATE) return List.of();
        Collection<KinematicContraption> contraptions = ship.getPlot().getContraptions();
        if (contraptions.isEmpty()) return List.of();
        int tick = ship.getLevel().getServer().getTickCount();
        if (tick != shapesTick) {
            SHAPES.clear();
            shapesTick = tick;
        }
        return SHAPES.computeIfAbsent(ship.getUniqueId(), id -> {
            List<IplContraptionShape> out = new ArrayList<>(contraptions.size());
            for (KinematicContraption contraption : contraptions) {
                if (!(contraption instanceof Entity entity) || entity.isRemoved()) continue;
                IplContraptionShape shape = IplContraptions.shape(entity, 1.0f);
                if (shape != null) out.add(shape);
            }
            return out;
        });
    }

    /** Grows plot bounds {@code [minX, minY, minZ, maxX, maxY, maxZ]} by what the contraptions cover or sweep. */
    static void extendBounds(ServerSubLevel ship, double[] box) {
        for (IplContraptionShape shape : shapes(ship)) {
            double[] b = shape.bounds();
            for (int i = 0; i < 3; i++) {
                box[i] = Math.min(box[i], b[i]);
                box[i + 3] = Math.max(box[i + 3], b[i + 3]);
            }
        }
    }

    /** World bounds of the body grown by everything its contraptions cover or sweep. */
    public static BoundingBox3d worldBounds(ServerSubLevel ship) {
        BoundingBox3d out = new BoundingBox3d(ship.boundingBox());
        for (IplContraptionShape shape : shapes(ship)) {
            double[] b = shape.bounds();
            out.expandTo(new BoundingBox3d(b[0], b[1], b[2], b[3], b[4], b[5]).transform(ship.logicalPose()));
        }
        return out;
    }

    private static int tetherPiece(IplPieceMap map, IplPieceMap.Edge[] edges, IplContraptionShape shape) {
        return map.classify(edges, shape.tx, shape.ty, shape.tz);
    }

    /** A contraption in piece {@code node} reaches {@code aperture} (plot frame of that piece): the session must open. */
    static boolean reaches(ServerSubLevel ship, IplPieceMap map, int node, IplPieceMap.Aperture aperture) {
        IplPieceMap.Edge[] edges = map.edges();
        for (IplContraptionShape shape : shapes(ship)) {
            if (shape.reaches(edges, tetherPiece(map, edges, shape), node, aperture)) return true;
        }
        return false;
    }

    /** Pieces some contraption of the body is in or may swing into over {@code edges}. */
    static BitSet held(ServerSubLevel ship, IplPieceMap map, IplPieceMap.Edge[] edges) {
        BitSet out = new BitSet();
        for (IplContraptionShape shape : shapes(ship)) out.or(shape.reached(edges, tetherPiece(map, edges, shape)));
        return out;
    }

    /**
     * A contraption flips with its body: not while one of its blocks is outside the flipping
     * subtree or lies across an opening out of it. Its blocks, not its swept volume: a turning
     * windmill does not wait until its whole circle has cleared the opening.
     */
    static boolean holdsFlip(ServerSubLevel ship, IplPieceMap map, IntPredicate inFlippingSubtree) {
        IplPieceMap.Edge[] edges = map.edges();
        for (IplContraptionShape shape : shapes(ship)) {
            BitSet nodes = shape.split(edges, tetherPiece(map, edges, shape)).nodes();
            for (int node = nodes.nextSetBit(0); node >= 0; node = nodes.nextSetBit(node + 1)) {
                if (!inFlippingSubtree.test(node)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ natives

    /** A kinematic contraption entered the scene as native collider {@code id}. */
    public static void onNativeAdded(KinematicContraption contraption, long scene, int id) {
        NATIVE.put(contraption, new NativeEntry(scene, id));
        if (contraption instanceof Entity entity) {
            IplContraptionShape shape = IplContraptions.shape(entity, 1.0f);
            if (shape != null) pushAnchor(contraption, shape);
        }
    }

    public static void onNativeRemoved(KinematicContraption contraption) {
        NATIVE.remove(contraption);
    }

    /** Before the body is sampled: the natives walk contraption contacts from the current tethers. */
    static void pushAnchors(ServerSubLevel ship) {
        for (IplContraptionShape shape : shapes(ship)) {
            if (shape.entity instanceof KinematicContraption contraption) pushAnchor(contraption, shape);
        }
    }

    private static void pushAnchor(KinematicContraption contraption, IplContraptionShape shape) {
        NativeEntry entry = NATIVE.get(contraption);
        if (entry == null || !IplRapierNatives.contraptionsSupported()) return;
        if (entry.x == shape.tx && entry.y == shape.ty && entry.z == shape.tz) return;
        entry.x = shape.tx;
        entry.y = shape.ty;
        entry.z = shape.tz;
        IplRapierNatives.setContraptionAnchor(entry.scene, entry.id, shape.tx, shape.ty, shape.tz);
    }
}
