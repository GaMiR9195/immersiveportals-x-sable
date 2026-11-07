package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/**
 * Which piece every bit of a sub-level's material belongs to (README, section 2).
 *
 * <p>A piece is a node of the body's session tree: {@link #NATIVE} is the body in its own
 * level, every straddle session owns one id ({@code 1..255}) for the part of the body that
 * went through its portal. Membership is a property of the material, not of the current
 * geometry: a plot voxel keeps the id it was given when its centre crossed an aperture
 * (front to back inside the rectangle: into the child piece, back to front: back to the
 * parent), and material that passes a portal plane BESIDE the frame keeps its piece.
 *
 * <p>The authoritative label sample also records each finite aperture in plot coordinates.
 * Between that sample and a displayed/solver pose, centre crossings are transported through
 * the observed rectangles in time order. Only voxels actually touching an aperture are cut
 * by its current plane. No predicted motion slab can reassign material outside the doorway.
 *
 * <p>The same map and the same {@link #classify rule} drive the natives' contact clipping,
 * raycasts, entity frames and rendering, on the server and on the client.
 */
public final class IplPieceMap {

    public static final int NATIVE = 0;
    public static final int MAX_PIECE = 255;
    public static final int SECTION_VOLUME = 4096;
    private static final Edge[] NO_EDGES = new Edge[0];

    private final Long2ObjectOpenHashMap<byte[]> sections = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    /** Labelled voxels per piece; {@link #NATIVE} counts only voxels inside labelled sections. */
    private final int[] counts = new int[MAX_PIECE + 1];
    private long revision;
    private volatile Edge[] edges = NO_EDGES;
    private static final long[] ONLY_NATIVE = {1L, 0L, 0L, 0L};
    private final Long2ObjectOpenHashMap<long[]> presentCache = new Long2ObjectOpenHashMap<>();
    private long presentRevision = -1;

    /** Index of a plot voxel inside its section: {@code x | y << 4 | z << 8} (vanilla compile order). */
    public static int index(int x, int y, int z) {
        return (x & 15) | (y & 15) << 4 | (z & 15) << 8;
    }

    public static long sectionKey(int x, int y, int z) {
        return SectionPos.asLong(x >> 4, y >> 4, z >> 4);
    }

    public int label(int x, int y, int z) {
        byte[] s = sections.get(sectionKey(x, y, z));
        return s == null ? NATIVE : s[index(x, y, z)] & 0xFF;
    }

    /** Labels of the section with {@link SectionPos#asLong} key {@code key}; null = all native. */
    @Nullable
    public byte[] section(long key) {
        return sections.get(key);
    }

    public LongSet sectionKeys() {
        return sections.keySet();
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** Whether any stored voxel centre carries {@code piece}; geometrically split material is separate. */
    public boolean has(int piece) {
        return piece >= 0 && piece <= MAX_PIECE && counts[piece] > 0;
    }

    public void setLabel(int x, int y, int z, int piece) {
        long key = sectionKey(x, y, z);
        byte[] s = sections.get(key);
        if (s == null) {
            if (piece == NATIVE) return;
            s = new byte[SECTION_VOLUME];
            sections.put(key, s);
            counts[NATIVE] += SECTION_VOLUME;
        }
        int i = index(x, y, z);
        int old = s[i] & 0xFF;
        if (old == piece) return;
        s[i] = (byte) piece;
        counts[old]--;
        counts[piece]++;
        dirty.add(key);
        revision++;
    }

    /**
     * Applies {@code f} to every stored label (re-rooting after a flip, ending pieces).
     * {@code f(NATIVE)} must be {@link #NATIVE}: sections without labels are not visited.
     */
    public void relabel(IntUnaryOperator f) {
        var it = sections.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<byte[]> e = it.next();
            byte[] s = e.getValue();
            boolean changed = false;
            boolean any = false;
            for (int i = 0; i < SECTION_VOLUME; i++) {
                int old = s[i] & 0xFF;
                int now = f.applyAsInt(old);
                if (now != old) {
                    s[i] = (byte) now;
                    counts[old]--;
                    counts[now]++;
                    changed = true;
                }
                any |= now != NATIVE;
            }
            if (changed) {
                dirty.add(e.getLongKey());
                revision++;
            }
            if (!any) {
                it.remove();
                counts[NATIVE] -= SECTION_VOLUME;
            }
        }
    }

    /** Moves every label by whole sections (the plot of a body moved to another slot). */
    public void translate(int dsx, int dsz) {
        if (dsx == 0 && dsz == 0) return;
        Long2ObjectOpenHashMap<byte[]> moved = new Long2ObjectOpenHashMap<>(sections.size());
        var it = sections.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<byte[]> e = it.next();
            long key = e.getLongKey();
            moved.put(SectionPos.asLong(SectionPos.x(key) + dsx, SectionPos.y(key), SectionPos.z(key) + dsz), e.getValue());
        }
        dirty.addAll(sections.keySet());
        sections.clear();
        sections.putAll(moved);
        dirty.addAll(sections.keySet());
        Edge[] shifted = new Edge[edges.length];
        for (int i = 0; i < shifted.length; i++) {
            Edge e = edges[i];
            shifted[i] = new Edge(e.parent, e.child, e.aperture.shift(dsx * 16.0, dsz * 16.0),
                e.reference.shift(dsx * 16.0, dsz * 16.0));
        }
        setEdges(shifted);
        revision++;
    }

    public void clear() {
        dirty.addAll(sections.keySet());
        sections.clear();
        Arrays.fill(counts, 0);
        setEdges(NO_EDGES);
        revision++;
    }

    private void tally(@Nullable byte[] s, int sign) {
        if (s == null) return;
        for (int i = 0; i < SECTION_VOLUME; i++) counts[s[i] & 0xFF] += sign;
    }

    /** Bumped by every label change (render plans and caches key on it). */
    public long revision() {
        return revision;
    }

    /** Labels present in one section as a 256-bit set (render planning); cached per revision. */
    public long[] present(long key) {
        if (presentRevision != revision) {
            presentCache.clear();
            presentRevision = revision;
        }
        long[] bits = presentCache.get(key);
        if (bits == null) {
            byte[] section = sections.get(key);
            if (section == null) return ONLY_NATIVE;
            bits = new long[4];
            for (byte b : section) {
                int l = b & 0xFF;
                bits[l >>> 6] |= 1L << (l & 63);
            }
            presentCache.put(key, bits);
        }
        return bits;
    }

    public static boolean contains(long[] present, int label) {
        return label >= 0 && label <= MAX_PIECE && (present[label >>> 6] & 1L << (label & 63)) != 0;
    }

    /** Whether {@code present} holds exactly one label. */
    public static int single(long[] present) {
        int found = -1;
        for (int w = 0; w < 4; w++) {
            long bits = present[w];
            if (bits == 0) continue;
            if (found >= 0 || Long.bitCount(bits) > 1) return -1;
            found = w * 64 + Long.numberOfTrailingZeros(bits);
        }
        return found;
    }

    /** Sections changed since the last call (natives and network deltas), then forgets them. */
    public long[] drainDirty() {
        long[] out = dirty.toLongArray();
        dirty.clear();
        return out;
    }

    public void setEdges(Edge[] edges) {
        this.edges = edges.length == 0 ? NO_EDGES : edges;
    }

    public Edge[] edges() {
        return edges;
    }

    /** Live edge, including its authoritative label-time aperture, or null. */
    @Nullable
    public Edge edge(int parent, int child) {
        for (Edge e : edges) if (e.parent == parent && e.child == child) return e;
        return null;
    }

    public Aperture reference(int parent, int child, Aperture fallback) {
        Edge edge = edge(parent, child);
        return edge == null ? fallback : edge.aperture;
    }

    // ------------------------------------------------------------------ classification

    /** The piece of a body point in plot coordinates (see the class comment). */
    public int classify(double x, double y, double z) {
        return classify(edges, x, y, z);
    }

    /** Exact observed-sweep rule shared with the native contact classifier. */
    public int classify(Edge[] edges, double x, double y, double z) {
        int vx = (int) Math.floor(x), vy = (int) Math.floor(y), vz = (int) Math.floor(z);
        int label = label(vx, vy, vz);
        int piece = voxelLabel(edges, vx, vy, vz, label);
        double cx = vx + 0.5, cy = vy + 0.5, cz = vz + 0.5;
        for (Edge edge : edges) {
            if (!edge.splits(label, piece, cx, cy, cz) || !edge.laterallyInside(x, y, z, 0.0)) continue;
            int target = edge.side(x, y, z) >= 0.0 ? edge.parent : edge.child;
            if (target != piece) return target;
        }
        return piece;
    }

    /** Centre ownership transported through observed finite crossings in time order. */
    public static int voxelLabel(Edge[] edges, int x, int y, int z, int label) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        int piece = label;
        double after = -1.0;
        for (int pass = 0; pass < edges.length; pass++) {
            double first = Double.POSITIVE_INFINITY;
            Edge next = null;
            for (Edge edge : edges) {
                double time = edge.crossing(piece, cx, cy, cz);
                if (time > after && time < first) { first = time; next = edge; }
            }
            if (next == null) break;
            piece = piece == next.parent ? next.child : next.parent;
            after = first;
        }
        return piece;
    }

    @Nullable
    public Edge zoneEdgeOfVoxel(int x, int y, int z, int label) {
        return zoneEdgeOfVoxel(edges, x, y, z, label);
    }

    @Nullable
    public static Edge zoneEdgeOfVoxel(Edge[] edges, int x, int y, int z, int label) {
        int i = zoneOfVoxel(edges, x, y, z, label);
        return i < 0 ? null : edges[i];
    }

    /** Only a genuinely split voxel needs a clipping plane; transferred blocks stay whole. */
    public static int zoneOfVoxel(Edge[] edges, int x, int y, int z, int label) {
        int piece = voxelLabel(edges, x, y, z, label);
        int nearest = -1;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < edges.length; i++) {
            Edge edge = edges[i];
            if (!edge.splits(label, piece, x + 0.5, y + 0.5, z + 0.5)) continue;
            double d = Math.abs(edge.side(x + 0.5, y + 0.5, z + 0.5));
            if (d < distance) { nearest = i; distance = d; }
        }
        return nearest;
    }

    // ------------------------------------------------------------------ edges

    /**
     * One live crossing in plot coordinates: material of {@link #parent} passing the aperture
     * becomes {@link #child}. The normal points to the parent (entry) side.
     */
    public static final class Edge {
        public final int parent;
        public final int child;
        public final Aperture aperture;
        /** Aperture at the authoritative label sample. */
        public final Aperture reference;
        /** Geometric split depth on each side of the plane. */
        public final double zone;
        /** Support radius of a voxel along the normal: {@code (|nx|+|ny|+|nz|) / 2}. */
        public final double reach;

        /** Aperture at the sample against which the voxel labels were made. */
        public Edge(int parent, int child, Aperture aperture, Aperture reference) {
            this.parent = parent;
            this.child = child;
            this.aperture = aperture;
            this.reference = reference;
            this.reach = aperture.voxelReach();
            this.zone = reach + 0.05;
        }

        public double crossing(int label, double x, double y, double z) {
            double old = reference.side(x, y, z), now = side(x, y, z);
            boolean forward = label == parent && old >= 0.0 && now < 0.0;
            boolean backward = label == child && old < 0.0 && now >= 0.0;
            return forward || backward
                ? IplApertureSweep.crossing(reference, aperture, x, y, z, backward ? 0.5 : 0.0)
                : Double.NaN;
        }

        public boolean splits(int original, int piece, double x, double y, double z) {
            return splits(original, piece, x, y, z, 0.0);
        }

        public boolean splits(int original, int piece, double x, double y, double z, double modelReach) {
            if (piece != parent && piece != child) return false;
            double now = side(x, y, z);
            if (piece == parent ? now < 0.0 : now >= 0.0) return false;
            if (original == piece) {
                double old = reference.side(x, y, z);
                if (piece == parent ? old < 0.0 : old >= 0.0) return false;
            }
            return Math.abs(now) <= reach + 0.05 + modelReach && laterallyInside(x, y, z, 0.0);
        }

        public double side(double x, double y, double z) {
            return aperture.side(x, y, z);
        }

        public boolean laterallyInside(double x, double y, double z, double margin) {
            return aperture.laterallyInside(x, y, z, margin);
        }
    }

    /**
     * A portal rectangle in some frame: centre, unit normal towards the entry side, unit
     * axes and half extents.
     */
    public record Aperture(double ox, double oy, double oz, double nx, double ny, double nz,
                           double wx, double wy, double wz, double halfW,
                           double hx, double hy, double hz, double halfH) {

        /** The portal in its own level's world frame. */
        public static Aperture of(Portal portal) {
            Vec3 o = portal.getOriginPos();
            Vec3 n = portal.getNormal();
            Vec3 w = portal.getAxisW();
            Vec3 h = portal.getAxisH();
            return new Aperture(o.x, o.y, o.z, n.x, n.y, n.z, w.x, w.y, w.z, portal.getWidth() * 0.5,
                h.x, h.y, h.z, portal.getHeight() * 0.5);
        }

        /** This world-frame rectangle in the plot frame of a body at {@code pose}. */
        public Aperture toPlot(Pose3dc pose) {
            Vector3d o = pose.transformPositionInverse(new Vector3d(ox, oy, oz));
            Vector3d n = pose.transformNormalInverse(new Vector3d(nx, ny, nz));
            Vector3d w = pose.transformNormalInverse(new Vector3d(wx, wy, wz));
            Vector3d h = pose.transformNormalInverse(new Vector3d(hx, hy, hz));
            double lw = w.length(), lh = h.length();
            n.normalize();
            w.div(lw);
            h.div(lh);
            return new Aperture(o.x, o.y, o.z, n.x, n.y, n.z, w.x, w.y, w.z, halfW * lw,
                h.x, h.y, h.z, halfH * lh);
        }

        public Aperture shift(double x, double z) {
            return new Aperture(ox + x, oy, oz + z, nx, ny, nz, wx, wy, wz, halfW, hx, hy, hz, halfH);
        }

        public double side(double x, double y, double z) {
            return (x - ox) * nx + (y - oy) * ny + (z - oz) * nz;
        }

        public double u(double x, double y, double z) {
            return (x - ox) * wx + (y - oy) * wy + (z - oz) * wz;
        }

        public double v(double x, double y, double z) {
            return (x - ox) * hx + (y - oy) * hy + (z - oz) * hz;
        }

        public boolean laterallyInside(double x, double y, double z, double margin) {
            return Math.abs(u(x, y, z)) <= halfW + margin && Math.abs(v(x, y, z)) <= halfH + margin;
        }

        public double voxelReach() {
            return 0.5 * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        }

        /** Corner {@code i} (0..3) of the rectangle. */
        public double[] corner(int i) {
            double su = (i & 1) == 0 ? -halfW : halfW;
            double sv = (i & 2) == 0 ? -halfH : halfH;
            return new double[]{ox + wx * su + hx * sv, oy + wy * su + hy * sv, oz + wz * su + hz * sv};
        }
    }

    // ------------------------------------------------------------------ network codec

    /** Full or partial (dirty keys only) label payload: {@code {s:[{k:long, l:byte[4096] | absent}]}}. */
    public CompoundTag encode(@Nullable long[] keys) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        if (keys == null) {
            var it = sections.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var e = it.next();
                list.add(sectionTag(e.getLongKey(), e.getValue()));
            }
            root.putBoolean("full", true);
        } else {
            for (long key : keys) list.add(sectionTag(key, sections.get(key)));
        }
        root.put("s", list);
        ListTag geometry = new ListTag();
        for (Edge edge : edges) {
            CompoundTag t = new CompoundTag();
            t.putInt("p", edge.parent); t.putInt("c", edge.child);
            Aperture a = edge.aperture;
            double[] values = {a.ox(), a.oy(), a.oz(), a.nx(), a.ny(), a.nz(), a.wx(), a.wy(), a.wz(),
                a.halfW(), a.hx(), a.hy(), a.hz(), a.halfH()};
            for (int i = 0; i < values.length; i++) t.putDouble("a" + i, values[i]);
            geometry.add(t);
        }
        root.put("e", geometry);
        return root;
    }

    private static CompoundTag sectionTag(long key, @Nullable byte[] labels) {
        CompoundTag t = new CompoundTag();
        t.putLong("k", key);
        if (labels != null) t.putByteArray("l", labels.clone());
        return t;
    }

    /** Applies a payload produced by {@link #encode}. */
    public void decode(CompoundTag root) {
        if (root.contains("e", Tag.TAG_LIST)) {
            ListTag geometry = root.getList("e", Tag.TAG_COMPOUND);
            Edge[] decoded = new Edge[geometry.size()];
            for (int i = 0; i < decoded.length; i++) {
                CompoundTag t = geometry.getCompound(i);
                Aperture a = new Aperture(t.getDouble("a0"), t.getDouble("a1"), t.getDouble("a2"),
                    t.getDouble("a3"), t.getDouble("a4"), t.getDouble("a5"), t.getDouble("a6"),
                    t.getDouble("a7"), t.getDouble("a8"), t.getDouble("a9"), t.getDouble("a10"),
                    t.getDouble("a11"), t.getDouble("a12"), t.getDouble("a13"));
                decoded[i] = new Edge(t.getInt("p"), t.getInt("c"), a, a);
            }
            setEdges(decoded);
        } else if (root.getBoolean("full")) setEdges(NO_EDGES);
        if (root.getBoolean("full")) {
            sections.clear();
            Arrays.fill(counts, 0);
        }
        ListTag list = root.getList("s", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            long key = t.getLong("k");
            byte[] labels = t.contains("l", Tag.TAG_BYTE_ARRAY) ? t.getByteArray("l") : null;
            tally(labels == null || labels.length != SECTION_VOLUME
                ? sections.remove(key) : sections.put(key, labels), -1);
            if (labels != null && labels.length == SECTION_VOLUME) tally(labels, 1);
        }
        revision++;
    }
}
