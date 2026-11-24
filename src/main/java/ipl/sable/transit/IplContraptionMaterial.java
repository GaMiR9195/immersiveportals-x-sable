package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.natives.IplRapierNatives;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Server side of contraptions on straddling bodies ({@link IplContraptionShape}). Their blocks are
 * material of the body like its voxels: they carry piece labels in the body's map, change piece
 * when their centre passes an aperture rectangle (the crossings are applied with the voxels'
 * ones, in time order), open the sessions they enter, hold the sessions they are in or swing
 * into, and the body flips only once they are through too. The natives clip contraption contacts
 * by the same labels.
 */
public final class IplContraptionMaterial {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-contraptions");
    /** Logs what the natives receive (`-Dipl.contraptionDiagnostics=true`). */
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ipl.contraptionDiagnostics");
    private static final java.util.Set<Entity> UNMOUNTED = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    /** Native id of a kinematic contraption and the label state last handed to it. */
    private static final class NativeEntry {
        final long scene;
        final int id;
        @Nullable IplPieceMap map;
        long revision = -1;

        NativeEntry(long scene, int id) {
            this.scene = scene;
            this.id = id;
        }
    }

    /** A contraption block passing an aperture: front to back when {@code forward}, at {@code time} of the sweep. */
    record Crossing(UUID contraption, long block, double time, boolean forward) {}

    private static final Map<KinematicContraption, NativeEntry> NATIVE = new WeakHashMap<>();
    /** Shapes of one server tick; contraptions move once per tick. */
    private static final Map<UUID, List<IplContraptionShape>> SHAPES = new HashMap<>();
    /**
     * Block centres at the end of the last sample, per contraption. The body is sampled at every
     * solver boundary: each sample sweeps the blocks from there, like the voxels from the last
     * sampled aperture, so no crossing between two samples is missed.
     */
    private static final Map<Entity, double[]> SAMPLED = new WeakHashMap<>();
    private static int shapesTick = Integer.MIN_VALUE;

    private IplContraptionMaterial() {}

    static List<IplContraptionShape> shapes(ServerSubLevel ship) {
        if (!IplContraptions.any()) return List.of();
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

    /**
     * Grows plot bounds {@code [minX, minY, minZ, maxX, maxY, maxZ]} by what the contraptions cover
     * or sweep. Runs for every body each tick (portal lookup), so it never walks contraption blocks.
     */
    static void extendBounds(ServerSubLevel ship, double[] box) {
        for (double[] b : reaches(ship)) {
            for (int i = 0; i < 3; i++) {
                box[i] = Math.min(box[i], b[i]);
                box[i + 3] = Math.max(box[i + 3], b[i + 3]);
            }
        }
    }

    /** World bounds of the body grown by everything its contraptions cover or sweep. */
    public static BoundingBox3d worldBounds(ServerSubLevel ship) {
        BoundingBox3d out = new BoundingBox3d(ship.boundingBox());
        for (double[] b : reaches(ship)) {
            out.expandTo(new BoundingBox3d(b[0], b[1], b[2], b[3], b[4], b[5]).transform(ship.logicalPose()));
        }
        return out;
    }

    /** Plot boxes holding each contraption of the body ({@link IplContraptionKind#reach}). */
    private static List<double[]> reaches(ServerSubLevel ship) {
        if (!IplContraptions.any()) return List.of();
        Collection<KinematicContraption> contraptions = ship.getPlot().getContraptions();
        if (contraptions.isEmpty()) return List.of();
        List<double[]> out = new ArrayList<>(contraptions.size());
        for (KinematicContraption contraption : contraptions) {
            if (!(contraption instanceof Entity entity) || entity.isRemoved()) continue;
            double[] reach = IplContraptions.reach(entity);
            if (reach != null) out.add(reach);
        }
        return out;
    }

    // ------------------------------------------------------------------ labels

    /**
     * Starts labelling contraptions the map does not know yet: every block takes the piece of the
     * body block the contraption hangs on (a contraption assembled or loaded while its body
     * straddles). Known contraptions keep their labels, also across flips and moves to another
     * dimension (the entity keeps its UUID).
     */
    static void seed(ServerSubLevel ship, IplPieceMap map) {
        IplPieceMap.Edge[] edges = map.edges();
        for (IplContraptionShape shape : shapes(ship)) {
            UUID id = shape.entity.getUUID();
            if (map.knowsContraption(id)) continue;
            map.addContraption(id, shape.packedBlocks(), map.classify(edges, shape.tx, shape.ty, shape.tz));
        }
    }

    /**
     * Contraption blocks that passed the aperture of edge {@code parent -> child} ({@code child < 0}
     * before the session opens) during the part {@code [from, to]} of this sample, while the aperture
     * moved from {@code before} to {@code now} (plot frame of {@code parent}). A block of the parent
     * passes front to back, one of the child back to front ({@code backwardMargin} around the
     * rectangle, like the voxels' bridge slack).
     */
    static List<Crossing> crossings(ServerSubLevel ship, IplPieceMap map, int parent, int child,
                                    IplPieceMap.Aperture before, IplPieceMap.Aperture now,
                                    double from, double to, double backwardMargin) {
        List<Crossing> out = null;
        for (IplContraptionShape shape : shapes(ship)) {
            double[] previous = SAMPLED.get(shape.entity);
            if (previous == null || previous.length != shape.centres.length) continue;
            UUID id = shape.entity.getUUID();
            Long2ByteMap labels = map.contraptionLabels(id);
            for (int i = 0; i < shape.blocks.length; i++) {
                int label = shape.label(labels, i);
                boolean forward = label == parent;
                if (!forward && (child < 0 || label != child)) continue;
                double t = shape.crossing(before, now, previous, from, to, i, forward, forward ? 0.0 : backwardMargin);
                if (Double.isNaN(t)) continue;
                if (out == null) out = new ArrayList<>(4);
                out.add(new Crossing(id, shape.blocks[i].asLong(), t, forward));
            }
        }
        return out == null ? List.of() : out;
    }

    /** End of a sample of the body: the next one sweeps its contraptions from where they are now. */
    static void sampled(ServerSubLevel ship) {
        for (IplContraptionShape shape : shapes(ship)) SAMPLED.put(shape.entity, shape.centres);
    }

    /**
     * A block of piece {@code node} lies across {@code now} (plot frame of that piece) with its
     * centre in front: the session must open, like for a body voxel touching the aperture.
     */
    static boolean entering(ServerSubLevel ship, IplPieceMap map, int node, IplPieceMap.Aperture now) {
        for (IplContraptionShape shape : shapes(ship)) {
            if (shape.entering(now, map.contraptionLabels(shape.entity.getUUID()), node)) return true;
        }
        return false;
    }

    /** Pieces some contraption of the body holds open over {@code edges} ({@link IplContraptionShape#held}). */
    static BitSet held(ServerSubLevel ship, IplPieceMap map, IplPieceMap.Edge[] edges) {
        BitSet out = new BitSet();
        for (IplContraptionShape shape : shapes(ship)) {
            out.or(shape.held(edges, map.contraptionLabels(shape.entity.getUUID())));
        }
        return out;
    }

    /**
     * A contraption ends its crossing with its body, in the same flip. The flip only waits for its
     * blocks outside the flipping subtree that have not passed the plane of {@code entrance} yet
     * (still in front of it or lying across it), so no block ends up inside the terrain behind the
     * exit. A block that went past the plane beside the frame keeps no ship waiting; it flips too.
     */
    static boolean holdsFlip(ServerSubLevel ship, IplPieceMap map, IntPredicate inFlippingSubtree,
                             IplPieceMap.Aperture entrance) {
        for (IplContraptionShape shape : shapes(ship)) {
            Long2ByteMap labels = map.contraptionLabels(shape.entity.getUUID());
            for (int i = 0; i < shape.blocks.length; i++) {
                if (inFlippingSubtree.test(shape.label(labels, i))) continue;
                if (entrance.side(shape.x(i), shape.y(i), shape.z(i)) >= -IplContraptionShape.BLOCK_RADIUS) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ natives

    /** A kinematic contraption entered the scene as native collider {@code id}; it gets labels with the body's next push. */
    public static void onNativeAdded(KinematicContraption contraption, long scene, int id) {
        NATIVE.put(contraption, new NativeEntry(scene, id));
    }

    public static void onNativeRemoved(KinematicContraption contraption) {
        NATIVE.remove(contraption);
    }

    private static int shapeSize(Entity entity) {
        IplContraptionShape shape = IplContraptions.shape(entity, 1.0f);
        return shape == null ? 0 : shape.blocks.length;
    }

    /** Hands the natives the labels of the body's contraptions, when they changed since the last push. */
    static void pushNatives(ServerSubLevel ship, IplPieceMap map) {
        if (!IplContraptions.any() || !IplRapierNatives.contraptionsSupported()) return;
        long revision = map.contraptionRevision();
        for (KinematicContraption contraption : ship.getPlot().getContraptions()) {
            NativeEntry entry = NATIVE.get(contraption);
            if (!(contraption instanceof Entity entity)) continue;
            if (entry == null) {
                if (DIAGNOSTICS && UNMOUNTED.add(entity)) {
                    LOG.info("[IPL-CONTRAPTION] {} has no native collider on its body: no labels", entity.getUUID());
                }
                continue;
            }
            if (entry.map == map && entry.revision == revision) continue;
            entry.map = map;
            entry.revision = revision;
            Long2ByteMap labels = map.contraptionLabels(entity.getUUID());
            int n = labels == null ? 0 : labels.size();
            int[] blocks = new int[n * 3];
            byte[] pieces = new byte[n];
            if (labels != null) {
                int i = 0;
                for (Long2ByteMap.Entry e : labels.long2ByteEntrySet()) {
                    long block = e.getLongKey();
                    blocks[i * 3] = BlockPos.getX(block);
                    blocks[i * 3 + 1] = BlockPos.getY(block);
                    blocks[i * 3 + 2] = BlockPos.getZ(block);
                    pieces[i++] = e.getByteValue();
                }
            }
            IplRapierNatives.setContraptionLabels(entry.scene, entry.id, blocks, pieces);
            if (DIAGNOSTICS) {
                LOG.info("[IPL-CONTRAPTION] labels of {} (native {}): {} of {} blocks out of the native piece",
                    entity.getUUID(), entry.id, n, shapeSize(entity));
            }
        }
    }
}
