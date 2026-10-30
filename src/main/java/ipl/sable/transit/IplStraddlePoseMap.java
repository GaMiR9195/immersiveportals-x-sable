package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * Frame mapping for straddling sub-levels: from a given context dimension, a ship
 * straddling INTO that dimension has a portal-mapped pose.
 *
 * <p>Server side the isometry comes from the active Atlas image session;
 * client side it is derived from the straddle portal found by
 * {@code SourceClipPortalFinder} (lazily, via the client-only lookup class).
 */
public final class IplStraddlePoseMap {

    private IplStraddlePoseMap() {}

    /**
     * Full portal isometry {@code P = translate(dest) ∘ R ∘ translate(-origin)}, snapshot
     * at session start (immune to the portal entity moving afterwards). Scale-1 only —
     * callers gate scaling before construction. Mirrors
     * {@code SableTransitOps.computeMappedPose}'s math; reused here so the collision /
     * interaction family maps identically to transit and rendering.
     */
    public static final class StraddleMapping {
        private final Vec3 origin;
        private final Vec3 dest;
        private final org.joml.Quaterniond rot;
        private final org.joml.Quaterniond inv;
        private final boolean identityRotation;

        private StraddleMapping(Vec3 origin, Vec3 dest, org.joml.Quaterniond rot,
                                boolean identityRotation) {
            this.origin = origin;
            this.dest = dest;
            this.rot = rot;
            this.inv = new org.joml.Quaterniond(rot).conjugate();
            this.identityRotation = identityRotation;
        }

        public static StraddleMapping of(qouteall.imm_ptl.core.portal.Portal portal) {
            DQuaternion q = portal.getRotationD();
            boolean identity = isApproxIdentity(q);
            org.joml.Quaterniond rot = identity || q == null
                ? new org.joml.Quaterniond()
                : new org.joml.Quaterniond(q.x, q.y, q.z, q.w);
            return new StraddleMapping(portal.getOriginPos(), portal.getDestPos(), rot, identity);
        }

        private static Vec3 rotate(org.joml.Quaterniond q, Vec3 v) {
            org.joml.Vector3d out = q.transform(new org.joml.Vector3d(v.x, v.y, v.z));
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

        public org.joml.Quaterniond mapQuat(org.joml.Quaterniondc q) {
            return new org.joml.Quaterniond(rot).mul(q);
        }

        /** Conjugate a DEST-frame rotation delta back into the source frame: R⁻¹·dq·R. */
        public org.joml.Quaterniond unmapRotationDelta(org.joml.Quaterniondc dq) {
            return new org.joml.Quaterniond(inv).mul(dq).mul(rot);
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
        public dev.ryanhcode.sable.companion.math.BoundingBox3d mapAabb(
            dev.ryanhcode.sable.companion.math.BoundingBox3dc box
        ) {
            dev.ryanhcode.sable.companion.math.BoundingBox3d out =
                new dev.ryanhcode.sable.companion.math.BoundingBox3d();
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
            return new StraddleMapping(
                dest, origin, new org.joml.Quaterniond(inv), identityRotation);
        }

        public boolean isIdentityRotation() {
            return identityRotation;
        }

        /** {@code next ∘ this}: apply this mapping first, then {@code next}. */
        public StraddleMapping then(StraddleMapping next) {
            Vec3 shifted = next.mapPoint(dest);
            org.joml.Quaterniond composed = new org.joml.Quaterniond(next.rot).mul(rot);
            return new StraddleMapping(origin, shifted, composed,
                identityRotation && next.identityRotation);
        }

        /** Translation of the rigid map {@code x -> shift + rot x}. */
        public Vec3 shift() {
            return mapPoint(Vec3.ZERO);
        }

        /** Approximate equality used to detect moved portals / cache keys. */
        public boolean sameAs(@org.jetbrains.annotations.Nullable StraddleMapping other) {
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
            return java.util.Objects.hash(origin, dest, rot.x, rot.y, rot.z, rot.w);
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
            return ipl.sable.client.IplClientProjections.isClientStraddling(sub);
        }
        return IplAtlasStraddleSession.hasSession(sub.getUniqueId());
    }

    /**
     * One straddle image's portal and isometry, plus its projection-tree node (null for
     * frames not backed by a session node).
     */
    public record StraddleFrame(
        @Nullable qouteall.imm_ptl.core.portal.Portal portal, StraddleMapping mapping,
        @Nullable IplProjectionTree.Node node
    ) {
        public StraddleFrame(@Nullable qouteall.imm_ptl.core.portal.Portal portal, StraddleMapping mapping) {
            this(portal, mapping, null);
        }
    }

    /** Visit every straddle of {@code sub} mapping INTO {@code ctx} (dest side). */
    public static void forEachStraddleInto(
        SubLevel sub, Level ctx,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal, StraddleMapping> visitor
    ) {
        if (sub == null || ctx == null) return;
        if (ctx.isClientSide()) {
            ipl.sable.client.IplClientProjections.forEachClientStraddleInto(sub, ctx, visitor);
            return;
        }
        IplAtlasStraddleSession.forEachSessionInto(sub, ctx, visitor);
    }

    /** Visit every straddle of {@code sub} exiting FROM {@code ctx} (its parent side). */
    public static void forEachStraddleFrom(
        SubLevel sub, Level ctx,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal, StraddleMapping> visitor
    ) {
        if (sub == null || ctx == null) return;
        if (ctx.isClientSide()) {
            ipl.sable.client.IplClientProjections.forEachClientStraddleFrom(sub, ctx, visitor);
            return;
        }
        IplAtlasStraddleSession.forEachSessionFrom(sub, ctx, visitor);
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
     * <p>Русский комментарий: раньше плоскость выхода для образа считалась как
     * mapping.mapPoint(origin) первого портала — для цепочек это неверно (нужна плоскость
     * ПОСЛЕДНЕГО портала пути), а дочерние сессии не вырезались вовсе. Теперь срезы
     * строятся по дереву проекций и совпадают с регионами физики (KEEP/EXCLUDE трубки),
     * с приближением трубки полупространством для коллизий сущностей.
     */
    public static java.util.List<RealHalfSpace> frameCuts(IplProjectionTree tree, @Nullable IplProjectionTree.Node node) {
        java.util.List<RealHalfSpace> out = new java.util.ArrayList<>(2);
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

        dev.ryanhcode.sable.companion.math.BoundingBox3dc shipBox = sub.boundingBox();
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
            dev.ryanhcode.sable.companion.math.BoundingBox3d box =
                new dev.ryanhcode.sable.companion.math.BoundingBox3d();
            box.set(mapping == null ? shipBox : mapping.mapAabb(shipBox));
            dev.ryanhcode.sable.companion.math.BoundingBox3d cut = box;
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
            return ipl.sable.client.IplClientProjections.getClientStraddleMappingInto(sub, contextLevel);
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
     * use, so filter and pose can never disagree) and keeps exactly that piece's blocks.
     */
    @Nullable
    public static java.util.function.Predicate<BlockPos> getBlockCollisionKeepFilter(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        IplProjectionTree.Node node = frame == null ? null : frame.node();
        if (node == null && sub.getLevel() != contextLevel) return null;
        return cutFilter(leadPose(sub), frameCuts(tree, node), SEAM_SUPPORT_MARGIN);
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
    public static java.util.List<LocalHalfSpace> getBlockCollisionKeepPlanes(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        IplProjectionTree tree = IplProjectionTree.of(sub);
        if (tree.isEmpty()) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        IplProjectionTree.Node node = frame == null ? null : frame.node();
        if (node == null && sub.getLevel() != contextLevel) return null;
        Pose3dc pose = leadPose(sub);
        java.util.List<LocalHalfSpace> parts = new java.util.ArrayList<>(2);
        for (RealHalfSpace h : frameCuts(tree, node)) {
            parts.add(localHalfSpace(pose, h.point(), h.keep()));
        }
        return parts.isEmpty() ? null : parts;
    }

    /** AND of real-frame half-space cuts as a plot-local block predicate. */
    @Nullable
    private static java.util.function.Predicate<BlockPos> cutFilter(
        Pose3dc pose, java.util.List<RealHalfSpace> cuts, double planeMargin
    ) {
        if (cuts.isEmpty()) return null;
        java.util.List<java.util.function.Predicate<BlockPos>> parts = new java.util.ArrayList<>(cuts.size());
        for (RealHalfSpace h : cuts) parts.add(buildKeepFilter(pose, h.point(), h.keep(), planeMargin));
        if (parts.size() == 1) return parts.get(0);
        return pos -> {
            for (java.util.function.Predicate<BlockPos> part : parts) {
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
     * <p>The cut planes are rebuilt on every collision query, so their frequency was never
     * the problem -- their PHASE was. Every plane was derived from {@code logicalPose()},
     * the pose at the START of the tick, while the boxes it trims are consumed throughout
     * the movement resolution of that same tick. For a slow body the difference is far
     * below the block granularity the old whole-block filter worked at, which is what the
     * original comment on {@code buildKeepFilter} relied on. That assumption died twice
     * over: the cut is now sub-block exact, and the bodies being cut can move very fast.
     * At speed the plane sits a whole tick of travel behind the geometry, and the entity
     * feels it as material that is still solid one tick after the portal should have
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
        if (previous == null) return now;

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
        org.joml.Vector3d lp = pose.transformPositionInverse(
            new org.joml.Vector3d(point.x, point.y, point.z));
        org.joml.Vector3d ln = pose.transformNormalInverse(
            new org.joml.Vector3d(keepNormal.x, keepNormal.y, keepNormal.z),
            new org.joml.Vector3d());
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
    public static dev.ryanhcode.sable.companion.math.BoundingBox3d clipLocalBoxKeeping(
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
            return new dev.ryanhcode.sable.companion.math.BoundingBox3d(
                minX, minY, minZ, maxX, maxY, maxZ);
        }
        double dStatic = plane.signedDistance(sx, sy, sz);
        if (dStatic <= 0.0) return null;
        px -= dPushed * nx;
        py -= dPushed * ny;
        pz -= dPushed * nz;
        return new dev.ryanhcode.sable.companion.math.BoundingBox3d(
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
    public static java.util.function.Predicate<BlockPos> getSourceHalfKeepFilter(
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
    public static java.util.function.Predicate<BlockPos> getSourceHalfKeepFilter(
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
    private static java.util.function.Predicate<BlockPos> buildKeepFilter(
        Pose3dc pose, Vec3 point, Vec3 keepNormal, double planeMargin
    ) {
        org.joml.Vector3d lp = pose.transformPositionInverse(
            new org.joml.Vector3d(point.x, point.y, point.z));
        org.joml.Vector3d ln = pose.transformNormalInverse(
            new org.joml.Vector3d(keepNormal.x, keepNormal.y, keepNormal.z),
            new org.joml.Vector3d());
        double keepThreshold = -planeMargin;
        // IPL fix (exact cut): test the block's real EXTENT against the half-space,
        // not its centre.
        //
        // The old test was `centre . n >= threshold`: a block was kept or dropped as a
        // WHOLE depending on which side its midpoint fell on. That quantises the cut to
        // the sub-level's block lattice ("obrez po deleniyam mesha"). For an oblique
        // portal (the 45-degree case) the physical boundary can therefore sit up to half
        // a block on the wrong side of the mathematical plane, in BOTH directions:
        // solid material surviving behind the portal (the chunk you can jump onto) and
        // support vanishing in front of it. Because the error depends on where each
        // block centre happens to fall, it is also asymmetric across the aperture --
        // material on one side, nothing on the other.
        //
        // Keeping a block when ANY part of its unit cube is still in the kept half is
        // the exact half-space test for that block's geometry: the support function of
        // an axis-aligned unit cube along the (plot-local) plane normal is
        // 0.5*(|nx|+|ny|+|nz|). Nothing with real material on the kept side is dropped,
        // and nothing lying entirely behind the plane is kept, at ANY plane
        // orientation -- the cut no longer follows the lattice.
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
        Iterable<BlockPos> all, java.util.function.Predicate<BlockPos> keep
    ) {
        return () -> new java.util.Iterator<>() {
            private final java.util.Iterator<BlockPos> in = all.iterator();
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
                if (!hasNext()) throw new java.util.NoSuchElementException();
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
        StraddleMapping mapping, qouteall.imm_ptl.core.portal.Portal portal,
        dev.ryanhcode.sable.companion.math.BoundingBox3dc shipBox, Vec3 position
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
        dev.ryanhcode.sable.companion.math.BoundingBox3d srcHalf =
            clipBoxKeeping(shipBox, srcPlanePoint, inward.scale(-1.0));
        dev.ryanhcode.sable.companion.math.BoundingBox3d dstHalf =
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
    private static dev.ryanhcode.sable.companion.math.BoundingBox3d clipBoxKeeping(
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box, Vec3 planePos, Vec3 keepNormal
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
        dev.ryanhcode.sable.companion.math.BoundingBox3d out =
            new dev.ryanhcode.sable.companion.math.BoundingBox3d();
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
        dev.ryanhcode.sable.companion.math.BoundingBox3dc box, Vec3 p
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
