package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Frame mapping for straddling sub-levels: from a given context dimension, a ship
 * straddling INTO that dimension has a portal-mapped pose.
 *
 * <p>Server side the isometry comes from the active Atlas image sessions; client side from
 * the session portals mirrored by {@code IplStraddleSessionStore}, chains included (via the
 * client-only {@link IplClientProjections}, loaded lazily on the client branch).
 */
public final class IplStraddlePoseMap {

    private IplStraddlePoseMap() {}

    /**
     * Full portal isometry {@code P = translate(dest) ∘ R ∘ translate(-origin)}, snapshot
     * at session start (immune to the portal entity moving afterwards). Scale-1 only —
     * callers gate scaling before construction. Mirrors
     * {@code IplTransitOps.computeMappedPose}'s math; reused here so the collision /
     * interaction family maps identically to transit and rendering.
     */
    public static final class StraddleMapping {
        private final Vec3 origin;
        private final Vec3 dest;
        private final Quaterniond rot;
        private final Quaterniond inv;

        private StraddleMapping(Vec3 origin, Vec3 dest, Quaterniond rot) {
            this.origin = origin;
            this.dest = dest;
            this.rot = rot;
            this.inv = new Quaterniond(rot).conjugate();
        }

        public static StraddleMapping of(Portal portal) {
            DQuaternion q = portal.getRotationD();
            Quaterniond rot = isApproxIdentity(q) ? new Quaterniond() : new Quaterniond(q.x, q.y, q.z, q.w);
            return new StraddleMapping(portal.getOriginPos(), portal.getDestPos(), rot);
        }

        private static Vec3 rotate(Quaterniond q, Vec3 v) {
            Vector3d out = q.transform(new Vector3d(v.x, v.y, v.z));
            return new Vec3(out.x, out.y, out.z);
        }

        public Vec3 mapPoint(Vec3 p) {
            Vec3 local = rotate(rot, p.subtract(origin));
            return dest.add(local);
        }

        public Vec3 unmapPoint(Vec3 p) {
            Vec3 local = rotate(inv, p.subtract(dest));
            return origin.add(local);
        }

        public Vec3 mapVec(Vec3 v) {
            return rotate(rot, v);
        }

        public Vec3 unmapVec(Vec3 v) {
            return rotate(inv, v);
        }

        public Quaterniond mapQuat(Quaterniondc q) {
            return new Quaterniond(rot).mul(q);
        }

        /** Full pose isometry (position + orientation; rotationPoint/scale copied). */
        public Pose3d mapPose(Pose3dc pose) {
            Pose3d out = new Pose3d(pose);
            Vec3 p = mapPoint(new Vec3(
                pose.position().x(), pose.position().y(), pose.position().z()));
            out.position().set(p.x, p.y, p.z);
            out.orientation().set(mapQuat(pose.orientation()));
            return out;
        }

        /** Enclosing axis-aligned box of the 8 mapped corners. */
        public BoundingBox3d mapAabb(
            BoundingBox3dc box
        ) {
            BoundingBox3d out =
                new BoundingBox3d();
            boolean first = true;
            for (int i = 0; i < 8; i++) {
                Vec3 corner = mapPoint(new Vec3(
                    (i & 1) == 0 ? box.minX() : box.maxX(),
                    (i & 2) == 0 ? box.minY() : box.maxY(),
                    (i & 4) == 0 ? box.minZ() : box.maxZ()));
                if (first) {
                    out.set(corner.x, corner.y, corner.z, corner.x, corner.y, corner.z);
                    first = false;
                } else {
                    out.expandTo(corner.x, corner.y, corner.z);
                }
            }
            return out;
        }

        /**
         * Exactly {@code mapAabb(box).intersects(query)}, behind a cheap conservative
         * pre-reject: a rigid map keeps every corner of {@code box} within its half-diagonal of
         * the mapped centre, so when the cube of that radius around the mapped centre misses
         * {@code query}, the mapped box misses it too and the 8-corner path is skipped.
         *
         * <p>Сплайс пространственных запросов ({@code CompanionIntersectMixin}) зовёт эту
         * проверку на КАЖДЫЙ запрос Sable, пока корабль стоит в портале: частицы, коллизии,
         * лучи. Почти все они далеко от образа корабля — одна точка вместо восьми.
         */
        public boolean mappedIntersects(BoundingBox3dc box, BoundingBox3dc query) {
            double hx = (box.maxX() - box.minX()) * 0.5;
            double hy = (box.maxY() - box.minY()) * 0.5;
            double hz = (box.maxZ() - box.minZ()) * 0.5;
            // Margin far above the rounding of the 8-corner path; a non-unit quaternion can
            // only scale (JOML may not normalise), never move a corner beyond |q|^2 * radius.
            double r = Math.sqrt(hx * hx + hy * hy + hz * hz) * Math.max(1.0, rot.lengthSquared())
                * (1.0 + 1.0e-6) + 1.0e-3;
            Vector3d c = rot.transform(new Vector3d(
                box.minX() + hx - origin.x, box.minY() + hy - origin.y, box.minZ() + hz - origin.z));
            double cx = dest.x + c.x;
            double cy = dest.y + c.y;
            double cz = dest.z + c.z;
            if (cx + r < query.minX() || cx - r > query.maxX()
                || cy + r < query.minY() || cy - r > query.maxY()
                || cz + r < query.minZ() || cz - r > query.maxZ()) {
                return false;
            }
            return mapAabb(box).intersects(query);
        }

        /**
         * The dest→source view of this isometry (endpoints swapped, rotation
         * conjugated). {@code inverse().mapX ≡ unmapX}.
         */
        public StraddleMapping inverse() {
            return new StraddleMapping(dest, origin, new Quaterniond(inv));
        }

        /** {@code next ∘ this}: apply this mapping first, then {@code next}. */
        public StraddleMapping then(StraddleMapping next) {
            Vec3 shifted = next.mapPoint(dest);
            Quaterniond composed = new Quaterniond(next.rot).mul(rot);
            return new StraddleMapping(origin, shifted, composed);
        }

        /** Translation of the rigid map {@code x -> shift + rot x}. */
        public Vec3 shift() {
            return mapPoint(Vec3.ZERO);
        }

        /** Approximate equality used to detect moved portals / cache keys. */
        public boolean sameAs(@Nullable StraddleMapping other) {
            if (other == null) return false;
            return shift().distanceToSqr(other.shift()) < 1.0e-12
                && Math.abs(Math.abs(rot.dot(other.rot)) - 1.0) < 1.0e-12;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StraddleMapping m && m.origin.equals(origin)
                && m.dest.equals(dest) && m.rot.equals(rot);
        }

        @Override
        public int hashCode() {
            return Objects.hash(origin, dest, rot.x, rot.y, rot.z, rot.w);
        }
    }

    /**
     * The full portal isometry mapping {@code sub}'s source frame into
     * {@code contextLevel}, or null when {@code sub} isn't straddling into that
     * dimension. Rotation-capable; scale stays gated at 1 by the providers.
     */
    @Nullable
    public static StraddleMapping getMappingInto(
        @Nullable SubLevel sub, @Nullable Level contextLevel
    ) {
        if (sub == null || contextLevel == null) return null;
        if (sub.getLevel() == contextLevel) return null; // native frame
        return getStraddleDestinationMapping(sub, contextLevel);
    }

    /**
     * Whether {@code sub} is currently straddling a portal at all, judged from
     * {@code contextLevel}'s side. Client: the straddle-portal finder (same source the
     * renderer and collision mapping use). Server: an active Atlas image session.
     */
    public static boolean isStraddling(@Nullable SubLevel sub, @Nullable Level contextLevel) {
        if (sub == null || contextLevel == null) return false;
        if (contextLevel.isClientSide()) {
            return IplClientProjections.isClientStraddling(sub);
        }
        return IplAtlasStraddleSession.hasSession(sub.getUniqueId());
    }

    /**
     * One straddle image's portal and isometry, plus its projection-tree node (null for
     * frames not backed by a session node).
     */
    public record StraddleFrame(
        @Nullable Portal portal, StraddleMapping mapping,
        @Nullable IplProjectionTree.Node node
    ) {
        public StraddleFrame(@Nullable Portal portal, StraddleMapping mapping) {
            this(portal, mapping, null);
        }
    }

    /** Visit every straddle of {@code sub} mapping INTO {@code ctx} (dest side). */
    public static void forEachStraddleInto(
        SubLevel sub, Level ctx,
        BiConsumer<Portal, StraddleMapping> visitor
    ) {
        if (sub == null || ctx == null) return;
        if (ctx.isClientSide()) {
            IplClientProjections.forEachClientStraddleInto(sub, ctx, visitor);
            return;
        }
        IplAtlasStraddleSession.forEachSessionInto(sub, ctx, visitor);
    }

    // ------------------------------------------------------------------ collision frame

    /** Blocks around an entity within which piece material decides its collision frame. */
    private static final int FRAME_PROBE = 2;
    /** Rank offset of a frame without material near the entity: behind every frame that has some. */
    private static final double NO_MATERIAL_NEARBY = 1.0e6;

    private static final class FrameMemo {
        @Nullable IplProjectionTree tree;
        @Nullable SubLevel sub;
        @Nullable Level ctx;
        @Nullable Vec3 position;
        @Nullable StraddleFrame frame;
    }

    private static final ThreadLocal<FrameMemo> FRAME_MEMO = ThreadLocal.withInitial(FrameMemo::new);

    /**
     * The interaction/collision frame for a position in {@code ctx}: null = the native frame,
     * otherwise the image whose piece has material nearest to {@code position}. Every frame
     * through which the body exists in {@code ctx} competes (the native piece and every node
     * into {@code ctx}, chains included), each measured against its OWN material -- labels plus
     * aperture zones, as in {@link PieceCut} -- so material that passed a portal plane beside
     * the frame still counts for the native frame. Ties go to the image; frames without material
     * within {@link #FRAME_PROBE} blocks rank behind the others by their whole box.
     */
    @Nullable
    public static StraddleFrame chooseCollisionFrame(SubLevel sub, Level ctx, Vec3 position) {
        if (sub == null || ctx == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        // Collision, its pose wraps and the frame attachment ask for one entity several times a
        // tick; trees are rebuilt every tick and on every session change.
        FrameMemo memo = FRAME_MEMO.get();
        if (memo.tree == tree && memo.sub == sub && memo.ctx == ctx && position.equals(memo.position)) {
            return memo.frame;
        }
        Pose3dc real = sub.logicalPose();
        BoundingBox3dc shipBox = sub.boundingBox();
        double[] best = {Double.MAX_VALUE};
        StraddleFrame[] chosen = {null};
        IplProjectionTree.forEachFrameIn(sub, ctx, node -> {
            Pose3dc pose = node == null ? real : node.mapping.mapPose(real);
            double d = nearestMaterialSq(sub, tree, node, pose.transformPositionInverse(position));
            if (d == Double.MAX_VALUE) {
                d = NO_MATERIAL_NEARBY
                    + distanceSqToBox(node == null ? shipBox : node.mapping.mapAabb(shipBox), position);
            }
            // The native frame is visited first; an image takes over when nearer or equal.
            if (node == null ? d < best[0] : d <= best[0]) {
                best[0] = d;
                chosen[0] = node == null ? null : new StraddleFrame(node.portal, node.mapping, node);
            }
        });
        memo.tree = tree;
        memo.sub = sub;
        memo.ctx = ctx;
        memo.position = position;
        memo.frame = chosen[0];
        return chosen[0];
    }

    /**
     * Squared distance from a plot point to the nearest block of {@code node}'s piece within
     * {@link #FRAME_PROBE} blocks; {@link Double#MAX_VALUE} when there is none.
     */
    private static double nearestMaterialSq(SubLevel sub, IplProjectionTree tree,
                                            @Nullable IplProjectionTree.Node node, Vec3 p) {
        Level blocks = sub.getLevel();
        if (blocks == null) return Double.MAX_VALUE;
        PieceCut cut = PieceCut.of(tree, node);
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        int bx = Mth.floor(p.x), by = Mth.floor(p.y), bz = Mth.floor(p.z);
        int x0 = Math.max(bx - FRAME_PROBE, bounds.minX()), x1 = Math.min(bx + FRAME_PROBE, bounds.maxX());
        int y0 = Math.max(by - FRAME_PROBE, bounds.minY()), y1 = Math.min(by + FRAME_PROBE, bounds.maxY());
        int z0 = Math.max(bz - FRAME_PROBE, bounds.minZ()), z1 = Math.min(bz + FRAME_PROBE, bounds.maxZ());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double best = Double.MAX_VALUE;
        for (int x = x0; x <= x1; x++) {
            double dx = Math.max(Math.max(x - p.x, p.x - (x + 1)), 0.0);
            for (int y = y0; y <= y1; y++) {
                double dy = Math.max(Math.max(y - p.y, p.y - (y + 1)), 0.0);
                for (int z = z0; z <= z1; z++) {
                    double dz = Math.max(Math.max(z - p.z, p.z - (z + 1)), 0.0);
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d >= best || !cut.keeps(x, y, z)) continue;
                    if (blocks.getBlockState(pos.set(x, y, z)).isAir()) continue;
                    best = d;
                }
            }
        }
        return best;
    }


    /**
     * Entity-collision variant of {@link #getMappingInto}. Same-dimension crossings have
     * one Level for both source and destination, so the normal frame lookup deliberately
     * returns null. Collision still needs the mapped pose when the entity is standing in
     * the destination-side copy of that same scene. Multi-straddle: the nearest image's
     * mapping (see {@link #chooseCollisionFrame}).
     */
    @Nullable
    public static StraddleMapping getCollisionMappingInto(
        @Nullable SubLevel sub, @Nullable Level contextLevel, AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        return frame == null ? null : frame.mapping();
    }

    /** Destination mapping even when source and destination share the same Level. */
    @Nullable
    public static StraddleMapping getStraddleDestinationMapping(
        @Nullable SubLevel sub, @Nullable Level contextLevel
    ) {
        if (sub == null || contextLevel == null) return null;
        if (contextLevel.isClientSide()) {
            // Client-only class; loaded lazily when this branch executes.
            return IplClientProjections.getClientStraddleMappingInto(sub, contextLevel);
        }
        return IplAtlasStraddleSession.getMappingInto(sub, contextLevel);
    }

    // ------------------------------------------------------------------ collision cut

    /**
     * The material of ONE piece of a straddling body as entity collision sees it, in plot
     * coordinates. A block belongs to the piece by its label; only blocks inside an aperture
     * zone are shared by the two pieces of that edge, and their boxes are trimmed at the
     * aperture plane ({@link #planeFor}). Material that passed a portal plane beside the frame
     * keeps its label, so nothing is ever cut by an infinite plane.
     */
    public static final class PieceCut {
        private final IplProjectionTree tree;
        private final int piece;
        private final IplPieceMap.Edge[] edges;
        /** Per edge: the aperture plane keeping this piece's side; null when the edge is foreign. */
        private final LocalHalfSpace[] planes;

        /** {@code lead}: the body pose the planes are placed at (null = the tree's own edges). */
        PieceCut(IplProjectionTree tree, int piece, @Nullable Pose3dc lead) {
            this.tree = tree;
            this.piece = piece;
            this.edges = tree.edges();
            this.planes = new LocalHalfSpace[edges.length];
            for (int i = 0; i < edges.length; i++) {
                IplPieceMap.Edge e = edges[i];
                if (e.parent != piece && e.child != piece) continue;
                IplProjectionTree.Node child = tree.node(e.child);
                IplPieceMap.Aperture a = lead == null || child == null ? e.aperture
                    : IplPieceMap.Aperture.of(child.portal)
                        .toPlot(child.parent == null ? lead : child.parent.mapping.mapPose(lead));
                double s = e.parent == piece ? 1.0 : -1.0; // the normal points to the parent side
                planes[i] = new LocalHalfSpace(a.ox(), a.oy(), a.oz(), s * a.nx(), s * a.ny(), s * a.nz());
            }
        }

        /** The piece of {@code node} ({@code null}: native) at the tree's own aperture planes. */
        public static PieceCut of(IplProjectionTree tree, @Nullable IplProjectionTree.Node node) {
            return new PieceCut(tree, node == null ? IplPieceMap.NATIVE : node.id, null);
        }

        /** Whether any part of plot block {@code pos} belongs to the piece. */
        public boolean keeps(BlockPos pos) {
            return keeps(pos.getX(), pos.getY(), pos.getZ());
        }

        public boolean keeps(int x, int y, int z) {
            int label = tree.labelOf(x, y, z);
            int e = IplPieceMap.zoneOfVoxel(edges, x, y, z, label);
            if (e < 0) return IplPieceMap.voxelLabel(edges, x, y, z, label) == piece;
            LocalHalfSpace plane = planes[e];
            return plane != null && plane.signedDistance(x + 0.5, y + 0.5, z + 0.5) + edges[e].reach > 0.0;
        }

        /** The plane trimming the boxes of a kept block; null when the whole block is the piece's. */
        @Nullable
        public LocalHalfSpace planeFor(int x, int y, int z) {
            int e = IplPieceMap.zoneOfVoxel(edges, x, y, z, tree.labelOf(x, y, z));
            return e < 0 ? null : planes[e];
        }
    }

    /**
     * The cut entity-vs-ship collision applies for one entity: the material of the frame chosen
     * for it ({@link #chooseCollisionFrame}, the same choice the pose wraps make, so filter and
     * pose can never disagree). Null when nothing is cut: the body straddles nothing, or it is
     * not present in {@code contextLevel} through any frame.
     */
    @Nullable
    public static PieceCut getBlockCollisionCut(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        IplProjectionTree.Node node = frame == null ? null : frame.node();
        if (node == null && sub.getLevel() != contextLevel) return null;
        return new PieceCut(tree, node == null ? IplPieceMap.NATIVE : node.id, leadPose(sub));
    }

    /**
     * A plane in the sub-level's PLOT-LOCAL frame: the kept side is {@code n · (p - point) >= 0},
     * {@code n} unit length.
     */
    public record LocalHalfSpace(double px, double py, double pz,
                                 double nx, double ny, double nz) {
        /** Signed distance of a plot-local point from the plane; positive = kept side. */
        public double signedDistance(double x, double y, double z) {
            return (x - px) * nx + (y - py) * ny + (z - pz) * nz;
        }
    }


    /**
     * How far ahead of the tick-start pose the cut is evaluated, in ticks. Half a tick is
     * the midpoint of the interval the cut is used across, so it is the value that
     * minimises the worst-case error in BOTH directions.
     */
    private static final double CUT_LEAD_TICKS = 0.5;

    /** Hard cap on the lead, in blocks, so a teleport can never fling the cut plane. */
    private static final double CUT_LEAD_CAP = 4.0;

    /**
     * DYNAMIC CUT CADENCE.
     *
     * <p>The aperture planes of a {@link PieceCut} are rebuilt on every collision query, so
     * their frequency is not the issue -- their PHASE is. {@code logicalPose()} is the pose at the START of the tick,
     * while the boxes it trims are consumed throughout the movement resolution of that same
     * tick. The cut is sub-block exact and the bodies being cut can move very fast, so a
     * plane derived from the start pose sits a whole tick of travel behind the geometry: the
     * entity feels material that is still solid one tick after the portal should have
     * removed it, or support that disappears one tick before it should.
     *
     * <p>Advancing the pose by the body's own last-tick displacement puts the plane where
     * it will be while it is actually being used, so the cut tracks the body instead of
     * trailing it. The lead is the sub-level's motion only: the plane belongs to the
     * portal, which does not move with the body, and expressing it in plot-local
     * coordinates through a newer pose is precisely the correction needed.
     */
    private static Pose3dc leadPose(SubLevel sub) {
        Pose3dc now = sub.logicalPose();
        Pose3dc previous = sub.lastPose();
        double dx = now.position().x() - previous.position().x();
        double dy = now.position().y() - previous.position().y();
        double dz = now.position().z() - previous.position().z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) return now;

        double scale = CUT_LEAD_TICKS;
        if (length * scale > CUT_LEAD_CAP) scale = CUT_LEAD_CAP / length;

        Pose3d out = new Pose3d(now);
        out.position().set(
            now.position().x() + dx * scale,
            now.position().y() + dy * scale,
            now.position().z() + dz * scale);
        return out;
    }

    /**
     * Cut one plot-local box with a {@link LocalHalfSpace}, keeping the side the normal
     * points to; null when the box lies entirely behind the plane.
     *
     * <p>Axis-aligned planes cut exactly. Oblique planes cut conservatively: the
     * behind-most corner is pushed onto the plane along the normal, which is the same
     * approximation ImmersivePortals' own {@code CollisionHelper.clipBox} uses. The result
     * is a slightly generous box, never a missing one -- physically the safe direction
     * for collision.
     */
    @Nullable
    public static BoundingBox3d clipLocalBoxKeeping(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        LocalHalfSpace plane
    ) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double px = nx > 0 ? minX : maxX;
        double py = ny > 0 ? minY : maxY;
        double pz = nz > 0 ? minZ : maxZ;
        double sx = nx > 0 ? maxX : minX;
        double sy = ny > 0 ? maxY : minY;
        double sz = nz > 0 ? maxZ : minZ;
        double dPushed = plane.signedDistance(px, py, pz);
        if (dPushed >= 0.0) {
            return new BoundingBox3d(
                minX, minY, minZ, maxX, maxY, maxZ);
        }
        double dStatic = plane.signedDistance(sx, sy, sz);
        if (dStatic <= 0.0) return null;
        px -= dPushed * nx;
        py -= dPushed * ny;
        pz -= dPushed * nz;
        return new BoundingBox3d(
            Math.min(px, sx), Math.min(py, sy), Math.min(pz, sz),
            Math.max(px, sx), Math.max(py, sy), Math.max(pz, sz));
    }


    /**
     * {@code all} filtered to positions passing {@code keep}. Lookahead advances the
     * source iterator inside {@code hasNext} — the same timing as vanilla's
     * {@code betweenClosed} AbstractIterator, so its MutableBlockPos reuse semantics
     * are preserved for for-each consumers.
     */
    public static Iterable<BlockPos> filterBlocks(
        Iterable<BlockPos> all, Predicate<BlockPos> keep
    ) {
        return () -> new Iterator<>() {
            private final Iterator<BlockPos> in = all.iterator();
            private BlockPos next;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                while (!hasNext && in.hasNext()) {
                    BlockPos candidate = in.next();
                    if (keep.test(candidate)) {
                        next = candidate;
                        hasNext = true;
                    }
                }
                return hasNext;
            }

            @Override
            public BlockPos next() {
                if (!hasNext()) throw new NoSuchElementException();
                hasNext = false;
                return next;
            }
        };
    }

    private static double distanceSqToBox(
        BoundingBox3dc box, Vec3 p
    ) {
        double cx = Math.max(box.minX(), Math.min(box.maxX(), p.x));
        double cy = Math.max(box.minY(), Math.min(box.maxY(), p.y));
        double cz = Math.max(box.minZ(), Math.min(box.maxZ(), p.z));
        double dx = p.x - cx, dy = p.y - cy, dz = p.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean isApproxIdentity(@Nullable DQuaternion q) {
        if (q == null) return true;
        return Math.abs(Math.abs(q.w) - 1.0) < 1e-4
            && Math.abs(q.x) < 1e-4 && Math.abs(q.y) < 1e-4 && Math.abs(q.z) < 1e-4;
    }

}
