package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

        /** The body's own chart (no portal): every map is the identity. */
        public static final StraddleMapping IDENTITY = new StraddleMapping(Vec3.ZERO, Vec3.ZERO, new Quaterniond());

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

    /**
     * The material {@code node}'s frame owns (null = the native piece), in the ship's REAL
     * frame (its own level, real pose); see {@link IplCollisionRegion}. Native piece: outside
     * every root prism. Node: inside the prisms of its whole path (each pulled back to the
     * real frame through its parent's mapping), outside its children's prisms and outside the
     * prism of every higher-priority sibling along the path -- exactly
     * {@link IplProjectionTree#pieceAt}, the physics regions and the render cells. Material that
     * went beyond a portal's plane BESIDE its aperture stays in the native frame.
     *
     * <p>{@code seam} widens each frame across its portal planes toward the neighbouring
     * frame: keep faces move forward, excluded prisms start deeper (collision support slack,
     * {@link #SEAM_SUPPORT_MARGIN}).
     */
    public static IplCollisionRegion frameRegion(
        IplProjectionTree tree, @Nullable IplProjectionTree.Node node, double seam
    ) {
        List<double[][]> exclude = new ArrayList<>(2);
        if (node == null) {
            for (IplProjectionTree.Node root : tree.roots()) {
                exclude.add(excluded(root.prism, null, seam));
            }
            return new IplCollisionRegion(List.of(), exclude);
        }
        List<double[]> keep = new ArrayList<>(10);
        for (IplProjectionTree.Node a = node; a != null; a = a.parent) {
            StraddleMapping back = a.parent == null ? null : a.parent.mapping;
            double[][] faces = a.prism.faces();
            faces[0][3] += seam;
            for (double[] face : faces) keep.add(unmapPlane(back, face));
            for (IplProjectionTree.Node sibling : tree.siblingsOf(a)) {
                if (sibling == a) break;
                exclude.add(excluded(sibling.prism, back, 0.0));
            }
        }
        for (IplProjectionTree.Node child : node.children) {
            exclude.add(excluded(child.prism, node.mapping, seam));
        }
        return new IplCollisionRegion(keep, exclude);
    }

    /** The faces of {@code prism} (portal face {@code seam} deeper) pulled back through {@code back}. */
    private static double[][] excluded(PortalPrism prism, @Nullable StraddleMapping back, double seam) {
        double[][] faces = prism.faces();
        faces[0][3] -= seam;
        for (int i = 0; i < faces.length; i++) faces[i] = unmapPlane(back, faces[i]);
        return faces;
    }

    /** A plane of {@code back}'s destination frame in its source frame (null = same frame). */
    private static double[] unmapPlane(@Nullable StraddleMapping back, double[] plane) {
        if (back == null) return plane;
        Vec3 normal = back.unmapVec(new Vec3(plane[0], plane[1], plane[2]));
        Vec3 point = back.unmapPoint(new Vec3(-plane[3] * plane[0], -plane[3] * plane[1], -plane[3] * plane[2]));
        return new double[]{normal.x, normal.y, normal.z, -normal.dot(point)};
    }

    /** A REAL-frame world plane in the plot-local frame of {@code pose}, unit normal again. */
    private static double[] localPlane(Pose3dc pose, double[] plane) {
        Vector3d point = pose.transformPositionInverse(
            new Vector3d(-plane[3] * plane[0], -plane[3] * plane[1], -plane[3] * plane[2]));
        Vector3d normal = pose.transformNormalInverse(
            new Vector3d(plane[0], plane[1], plane[2]), new Vector3d());
        double length = normal.length();
        if (length > 1.0e-12) normal.div(length);
        return new double[]{normal.x, normal.y, normal.z, -normal.dot(point)};
    }

    /**
     * The interaction/collision frame for a position in {@code ctx}: null = the native
     * frame; otherwise the frame of the NEAREST present image. Every frame through which
     * the ship exists in {@code ctx} competes (native piece and every session node into
     * {@code ctx}, chains included), each measured against the material it physically owns
     * ({@link #frameRegion}, in the real frame: the mappings are isometries). Ties resolve to
     * the image (legacy behaviour).
     */
    @Nullable
    public static StraddleFrame chooseCollisionFrame(SubLevel sub, Level ctx, Vec3 position) {
        if (sub == null || ctx == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;

        BoundingBox3dc shipBox = sub.boundingBox();
        double[] box = {
            shipBox.minX(), shipBox.minY(), shipBox.minZ(),
            shipBox.maxX(), shipBox.maxY(), shipBox.maxZ()};
        boolean nativeHere = sub.getLevel() == ctx;

        double[] best = {Double.MAX_VALUE};
        StraddleFrame[] chosen = {null};
        IplProjectionTree.forEachFrameIn(sub, ctx, node -> {
            if (node != null && nativeHere) {
                // Same dimension: an image only claims positions past ITS exit plane.
                Vec3 exitPoint = node.portal.getDestPos();
                Vec3 exitInward = node.portal.getContentDirection();
                if (exitInward.dot(position.subtract(exitPoint)) < 0.0) return;
            }
            Vec3 real = node == null ? position : node.mapping.unmapPoint(position);
            double d = frameRegion(tree, node, 0.0).distanceSq(box, real.x, real.y, real.z);
            if (node == null) {
                if (d < best[0]) best[0] = d;
                return; // native: chosen stays null unless an image is nearer/equal
            }
            if (d <= best[0]) {
                best[0] = d;
                chosen[0] = new StraddleFrame(node.portal, node.mapping, node);
            }
        });
        return chosen[0];
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

    /**
     * Blocks. Slack across the portal planes for COLLISION only (raycast/wireframe stays
     * strict): how far each frame's piece reaches into the neighbouring frame. The collision
     * frame is chosen by ENTITY center, so an entity whose center hasn't crossed the plane
     * yet stands in a frame that no longer owns the support just past it. Keeping a band
     * alive in BOTH frames makes the seam load-bearing throughout the frame handoff; 0.9
     * covers the worst case (half block + player half-width, any plane orientation).
     */
    private static final double SEAM_SUPPORT_MARGIN =
        Double.parseDouble(System.getProperty("ipl.sable.clip.seamSupportMargin", "0.0"));

    /**
     * Plot-local material of the collision frame for an entity at {@code entityBounds}, or
     * null when no clipping applies. The frame is {@link #chooseCollisionFrame}'s, the same
     * choice the pose wraps use, so region and pose can never disagree; the region is that
     * frame's piece ({@link #frameRegion}) at the lead pose.
     */
    @Nullable
    public static IplCollisionRegion getBlockCollisionRegion(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        IplProjectionTree.Node node = frame == null ? null : frame.node();
        if (node == null && sub.getLevel() != contextLevel) return null;
        Pose3dc pose = leadPose(sub);
        IplCollisionRegion region = frameRegion(tree, node, SEAM_SUPPORT_MARGIN)
            .map(plane -> localPlane(pose, plane));
        return region.isUnbounded() ? null : region;
    }

    /**
     * Whole-block keep-filter of {@link #getBlockCollisionRegion}: generous (a block stays
     * when any part of its cube may be in the piece), the collision boxes are trimmed to the
     * piece afterwards ({@link IplStraddleCollisionClip}).
     */
    @Nullable
    public static Predicate<BlockPos> getBlockCollisionKeepFilter(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        IplCollisionRegion region = getBlockCollisionRegion(sub, contextLevel, entityBounds);
        return region == null ? null : blockFilter(region);
    }

    public static Predicate<BlockPos> blockFilter(IplCollisionRegion region) {
        return pos -> region.keepsCube(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.5);
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
     * <p>The cut planes are rebuilt on every collision query, so their frequency is not the
     * issue -- their PHASE is. {@code logicalPose()} is the pose at the START of the tick,
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

    public static boolean isApproxIdentity(@Nullable DQuaternion q) {
        if (q == null) return true;
        return Math.abs(Math.abs(q.w) - 1.0) < 1e-4
            && Math.abs(q.x) < 1e-4 && Math.abs(q.y) < 1e-4 && Math.abs(q.z) < 1e-4;
    }

}
