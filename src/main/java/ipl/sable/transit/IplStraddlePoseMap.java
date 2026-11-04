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
     * One keep half-space of a frame, expressed in the ship's REAL frame (its own level,
     * real pose): material is kept where {@code (p - point) . keep >= 0}.
     */
    public record RealHalfSpace(Vec3 point, Vec3 keep) {}

    /**
     * The half-space cuts of one frame (null = native piece), all in the REAL frame:
     * native piece = front side of every root portal; node = beyond side of every portal
     * of its path (each pulled back to the real frame through its parent's mapping) and
     * front side of every child portal (pulled back through the node's own mapping).
     *
     * <p>Срезы строятся по дереву проекций: у цепочки это плоскость ПОСЛЕДНЕГО портала пути
     * плюс вырезы дочерних сессий. Они совпадают с регионами физики (KEEP/EXCLUDE трубки),
     * трубка для коллизий сущностей приближается полупространством.
     */
    public static List<RealHalfSpace> frameCuts(IplProjectionTree tree, @Nullable IplProjectionTree.Node node) {
        List<RealHalfSpace> out = new ArrayList<>(2);
        if (node == null) {
            for (IplProjectionTree.Node root : tree.roots()) {
                out.add(new RealHalfSpace(root.portal.getOriginPos(), root.portal.getNormal()));
            }
            return out;
        }
        for (IplProjectionTree.Node a = node; a != null; a = a.parent) {
            Vec3 point = a.portal.getOriginPos();
            Vec3 keep = a.portal.getNormal().scale(-1.0);
            if (a.parent != null) {
                point = a.parent.mapping.unmapPoint(point);
                keep = a.parent.mapping.unmapVec(keep);
            }
            out.add(new RealHalfSpace(point, keep));
        }
        for (IplProjectionTree.Node child : node.children) {
            out.add(new RealHalfSpace(
                node.mapping.unmapPoint(child.portal.getOriginPos()),
                node.mapping.unmapVec(child.portal.getNormal())));
        }
        return out;
    }

    /**
     * The interaction/collision frame for a position in {@code ctx}: null = the native
     * frame; otherwise the frame of the NEAREST present image. Every frame through which
     * the ship exists in {@code ctx} competes (native piece and every session node into
     * {@code ctx}, chains included), each measured against its CUT box (the part that
     * physically exists). Ties resolve to the image (legacy behaviour).
     */
    @Nullable
    public static StraddleFrame chooseCollisionFrame(SubLevel sub, Level ctx, Vec3 position) {
        if (sub == null || ctx == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;

        BoundingBox3dc shipBox = sub.boundingBox();
        boolean nativeHere = sub.getLevel() == ctx;

        double[] best = {Double.MAX_VALUE};
        StraddleFrame[] chosen = {null};
        IplProjectionTree.forEachFrameIn(sub, ctx, node -> {
            StraddleMapping mapping = node == null ? null : node.mapping;
            if (node != null && nativeHere) {
                // Same dimension: an image only claims positions past ITS exit plane.
                Vec3 exitPoint = node.portal.getDestPos();
                Vec3 exitInward = node.portal.getContentDirection();
                if (exitInward.dot(position.subtract(exitPoint)) < 0.0) return;
            }
            BoundingBox3d box =
                new BoundingBox3d();
            box.set(mapping == null ? shipBox : mapping.mapAabb(shipBox));
            BoundingBox3d cut = box;
            for (RealHalfSpace h : frameCuts(tree, node)) {
                if (cut == null) break;
                Vec3 point = mapping == null ? h.point() : mapping.mapPoint(h.point());
                Vec3 keep = mapping == null ? h.keep() : mapping.mapVec(h.keep());
                cut = clipBoxKeeping(cut, point, keep);
            }
            double d = cut == null ? Double.MAX_VALUE : distanceSqToBox(cut, position);
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
     * Blocks. Plane slack for COLLISION filters only (raycast/wireframe stays strict).
     * The filters cut by block CENTER but the collision frame is chosen by ENTITY
     * center — without slack, an entity whose center hasn't crossed the plane, standing
     * on a block whose center has, finds its support dropped by its own frame while the
     * block's image exists only in the other frame: a half-block dead zone you fall
     * through exactly at the portal plane. Keeping a one-block band alive in BOTH
     * frames makes the seam load-bearing throughout the frame handoff; 0.9 covers the
     * worst case (half block + player half-width, any plane orientation).
     */
    private static final double SEAM_SUPPORT_MARGIN =
        Double.parseDouble(System.getProperty("ipl.sable.clip.seamSupportMargin", "0.0"));

    /**
     * Plot-local block keep-filter for entity-vs-ship collision while the ship straddles
     * a portal, or null when no clipping applies. Selects the frame the collision code
     * uses for this entity ({@link #chooseCollisionFrame}, the same choice the pose wraps
     * use, so filter and pose can never disagree) and keeps exactly that piece's blocks:
     * the frame's half-space cuts, minus space yielded to a higher-priority sibling.
     */
    @Nullable
    public static Predicate<BlockPos> getBlockCollisionKeepFilter(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        IplProjectionTree.Node node = frame == null ? null : frame.node();
        if (node == null && sub.getLevel() != contextLevel) return null;
        Pose3dc pose = leadPose(sub);
        Predicate<BlockPos> cut = cutFilter(pose, frameCuts(tree, node), SEAM_SUPPORT_MARGIN);
        if (node == null || !tree.yieldsAnything(node)) return cut;
        // Space contested by several portals belongs to one piece only (sibling priority).
        Predicate<BlockPos> owned =
            pos -> !tree.yieldsAt(node, pose.transformPosition(Vec3.atCenterOf(pos)));
        return cut == null ? owned : cut.and(owned);
    }

    /**
     * A portal half-space already expressed in the sub-level's PLOT-LOCAL frame:
     * the kept side is {@code n · (p - point) >= 0}. {@code n} is unit length.
     */
    public record LocalHalfSpace(double px, double py, double pz,
                                 double nx, double ny, double nz) {
        /** Signed distance of a plot-local point from the plane; positive = kept side. */
        public double signedDistance(double x, double y, double z) {
            return (x - px) * nx + (y - py) * ny + (z - pz) * nz;
        }
    }

    /**
     * The same cut(s) {@link #getBlockCollisionKeepFilter} applies, but handed back as
     * geometry instead of a whole-block predicate, so callers can trim individual
     * collision boxes at the portal plane instead of keeping or dropping entire blocks.
     *
     * <p>Null when no clipping applies. A box must survive every returned cut.
     */
    @Nullable
    public static List<LocalHalfSpace> getBlockCollisionKeepPlanes(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        IplProjectionTree.Node node = frame == null ? null : frame.node();
        if (node == null && sub.getLevel() != contextLevel) return null;
        Pose3dc pose = leadPose(sub);
        List<LocalHalfSpace> parts = new ArrayList<>(2);
        for (RealHalfSpace h : frameCuts(tree, node)) {
            parts.add(localHalfSpace(pose, h.point(), h.keep()));
        }
        return parts.isEmpty() ? null : parts;
    }

    /** AND of real-frame half-space cuts as a plot-local block predicate. */
    @Nullable
    private static Predicate<BlockPos> cutFilter(
        Pose3dc pose, List<RealHalfSpace> cuts, double planeMargin
    ) {
        if (cuts.isEmpty()) return null;
        List<Predicate<BlockPos>> parts = new ArrayList<>(cuts.size());
        for (RealHalfSpace h : cuts) parts.add(buildKeepFilter(pose, h.point(), h.keep(), planeMargin));
        if (parts.size() == 1) return parts.get(0);
        return pos -> {
            for (Predicate<BlockPos> part : parts) {
                if (!part.test(pos)) return false;
            }
            return true;
        };
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

    private static LocalHalfSpace localHalfSpace(Pose3dc pose, Vec3 point, Vec3 keepNormal) {
        Vector3d lp = pose.transformPositionInverse(
            new Vector3d(point.x, point.y, point.z));
        Vector3d ln = pose.transformNormalInverse(
            new Vector3d(keepNormal.x, keepNormal.y, keepNormal.z),
            new Vector3d());
        double length = Math.sqrt(ln.x * ln.x + ln.y * ln.y + ln.z * ln.z);
        if (length > 1.0e-12) {
            ln.x /= length;
            ln.y /= length;
            ln.z /= length;
        }
        return new LocalHalfSpace(lp.x, lp.y, lp.z, ln.x, ln.y, ln.z);
    }

    /**
     * Cut one plot-local box with a {@link LocalHalfSpace}, keeping the side the normal
     * points to; null when the box lies entirely behind the plane.
     *
     * <p>Axis-aligned planes cut exactly. Oblique planes cut conservatively: the
     * behind-most corner is pushed onto the plane along the normal, which is the same
     * approximation ImmersivePortals' own {@code CollisionHelper.clipBox} uses, and the
     * same one {@link #clipBoxKeeping} already applies to whole-ship boxes. The result
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
     * Keep-filter for plot blocks reached through the ship's NATIVE frame (real pose):
     * drops the through-half — past the portal plane across its full infinite span. This
     * matches the native portal-plane contact clip for an active straddle session. Null
     * when the ship isn't straddling out of {@code contextLevel}.
     */
    @Nullable
    public static Predicate<BlockPos> getSourceHalfKeepFilter(
        @Nullable SubLevel sub, @Nullable Level contextLevel
    ) {
        return getSourceHalfKeepFilter(sub, contextLevel, 0.0);
    }

    /**
     * {@code planeMargin} extends the kept side past the plane (collision support
     * slack). Multi-straddle: the AND of EVERY exiting session's cut — a block must be
     * on the kept side of all of them to be physically present on the source side.
     */
    @Nullable
    public static Predicate<BlockPos> getSourceHalfKeepFilter(
        @Nullable SubLevel sub, @Nullable Level contextLevel, double planeMargin
    ) {
        if (sub == null || contextLevel == null) return null;
        if (sub.getLevel() != contextLevel) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        // Source side keeps +portal normal (crossing direction is -normal, the same
        // convention as the transit controller's parity rule).
        return cutFilter(leadPose(sub), frameCuts(tree, null), planeMargin);
    }

    /**
     * Compile a world-frame half-space cut into a plot-local block-center predicate:
     * transformed once, one dot product per block.
     * Uses the end-of-tick pose rather than any substep lerp — within-tick ship motion
     * is far below the block-center granularity this test operates at.
     */
    private static Predicate<BlockPos> buildKeepFilter(
        Pose3dc pose, Vec3 point, Vec3 keepNormal, double planeMargin
    ) {
        Vector3d lp = pose.transformPositionInverse(
            new Vector3d(point.x, point.y, point.z));
        Vector3d ln = pose.transformNormalInverse(
            new Vector3d(keepNormal.x, keepNormal.y, keepNormal.z),
            new Vector3d());
        double keepThreshold = -planeMargin;
        // Test the block's real EXTENT against the half-space, not its centre. A centre test
        // keeps or drops each block WHOLE and quantises the cut to the block lattice: for an
        // oblique (45-degree) portal the physical boundary would sit up to half a block on
        // the wrong side of the plane, asymmetrically across the aperture -- solid material
        // behind the portal on one side, missing support in front of it on the other.
        //
        // A block is kept when ANY part of its unit cube is in the kept half: the support
        // function of an axis-aligned unit cube along the (plot-local) plane normal is
        // 0.5*(|nx|+|ny|+|nz|). Nothing with material on the kept side is dropped and
        // nothing lying entirely behind the plane is kept, at ANY plane orientation.
        double halfExtentAlongNormal =
            0.5 * (Math.abs(ln.x) + Math.abs(ln.y) + Math.abs(ln.z));
        return pos -> (pos.getX() + 0.5 - lp.x) * ln.x
                    + (pos.getY() + 0.5 - lp.y) * ln.y
                    + (pos.getZ() + 0.5 - lp.z) * ln.z
                    + halfExtentAlongNormal >= keepThreshold;
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

    /**
     * Same-dimension mapped-half membership: past the MAPPED destination plane AND no
     * farther from the mapped ship image than from the real ship. The second condition is
     * load-bearing for rotated pairs — the dest portal's INFINITE half-space can sweep
     * across the source-side region too, and without the proximity check approaching-side
     * entities would be handed the mapped pose (and fall through the real hull).
     */
    public static boolean isInMappedHalf(
        StraddleMapping mapping, Portal portal,
        BoundingBox3dc shipBox, Vec3 position
    ) {
        Vec3 srcPlanePoint = portal.getOriginPos();
        Vec3 inward = portal.getNormal().scale(-1.0);
        Vec3 destPlanePoint = mapping.mapPoint(srcPlanePoint);
        Vec3 destInward = mapping.mapVec(inward);
        if (destInward.dot(position.subtract(destPlanePoint)) < 0.0) return false;

        // Proximity against the CLIPPED halves, not the full boxes. Mid-crossing, the
        // full-ship AABB and its mapped image overlap the seam region (close or rotated
        // pairs especially), so an entity standing on real source-side deck sat inside
        // BOTH boxes — the 0-vs-0 tie resolved to "mapped" and collision flipped to the
        // wrong frame around halfway. The halves that physically exist are the real box
        // BEFORE the source plane and the image PAST the dest plane; measure those.
        BoundingBox3d srcHalf =
            clipBoxKeeping(shipBox, srcPlanePoint, inward.scale(-1.0));
        BoundingBox3d dstHalf =
            clipBoxKeeping(mapping.mapAabb(shipBox), destPlanePoint, destInward);
        double dSource = srcHalf == null ? Double.MAX_VALUE : distanceSqToBox(srcHalf, position);
        double dMapped = dstHalf == null ? Double.MAX_VALUE : distanceSqToBox(dstHalf, position);
        return dMapped <= dSource;
    }

    /**
     * Cut a box with a plane, keeping the side {@code keepNormal} points to; null when
     * fully cut. Axis-aligned planes cut exactly; oblique planes conservatively (the
     * behind-most corner is pushed onto the plane along the normal — same approximation
     * ImmersivePortals' own CollisionHelper.clipBox uses).
     */
    @Nullable
    private static BoundingBox3d clipBoxKeeping(
        BoundingBox3dc box, Vec3 planePos, Vec3 keepNormal
    ) {
        Vec3 n = keepNormal.normalize();
        double px = n.x > 0 ? box.minX() : box.maxX();
        double py = n.y > 0 ? box.minY() : box.maxY();
        double pz = n.z > 0 ? box.minZ() : box.maxZ();
        double sx = n.x > 0 ? box.maxX() : box.minX();
        double sy = n.y > 0 ? box.maxY() : box.minY();
        double sz = n.z > 0 ? box.maxZ() : box.minZ();
        double dPushed = (px - planePos.x) * n.x + (py - planePos.y) * n.y
                       + (pz - planePos.z) * n.z;
        BoundingBox3d out =
            new BoundingBox3d();
        if (dPushed >= 0.0) {
            out.set(box);
            return out;
        }
        double dStatic = (sx - planePos.x) * n.x + (sy - planePos.y) * n.y
                       + (sz - planePos.z) * n.z;
        if (dStatic <= 0.0) return null;
        px -= dPushed * n.x;
        py -= dPushed * n.y;
        pz -= dPushed * n.z;
        out.set(
            Math.min(px, sx), Math.min(py, sy), Math.min(pz, sz),
            Math.max(px, sx), Math.max(py, sy), Math.max(pz, sz));
        return out;
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
