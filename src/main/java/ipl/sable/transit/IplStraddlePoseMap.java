package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * Frame mapping for straddling hosted sub-levels: from a given context dimension, a ship
 * straddling INTO that dimension has a portal-mapped pose (translation-only portal pairs —
 * the pose translates by the portal offset; orientation is unchanged).
 *
 * <p>Server side the offset comes from the active {@link IplStraddleTerrainClone} session;
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

        /** Pure-translation mapping (legacy terrain-clone interop). */
        public static StraddleMapping ofTranslation(BlockPos offset) {
            return new StraddleMapping(Vec3.ZERO,
                new Vec3(offset.getX(), offset.getY(), offset.getZ()),
                new org.joml.Quaterniond(), true);
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
         * conjugated). {@code inverse().mapX ≡ unmapX} — lets role-parametrized code
         * (the mirrored straddle servo) treat both authority directions uniformly.
         */
        public StraddleMapping inverse() {
            return new StraddleMapping(
                dest, origin, new org.joml.Quaterniond(inv), identityRotation);
        }

        public boolean isIdentityRotation() {
            return identityRotation;
        }

        /**
         * The legacy BlockPos offset when this mapping is a block-aligned pure
         * translation; null otherwise. Old-API shims degrade through this, so
         * un-migrated callers behave exactly as before rotation support.
         */
        @Nullable
        public BlockPos blockOffsetOrNull() {
            if (!identityRotation) return null;
            return blockAligned(dest.subtract(origin));
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
        if (!IplDimAgnostic.isHosted(sub)) return null;
        if (IplDimAgnostic.getParentLevel(sub) == contextLevel) return null; // native frame
        return getStraddleDestinationMapping(sub, contextLevel);
    }

    /**
     * Legacy BlockPos view of {@link #getMappingInto}: non-null only for block-aligned
     * translation-only pairs, exactly the pre-rotation behavior for un-migrated callers.
     */
    @Nullable
    public static BlockPos getOffsetInto(@Nullable SubLevel sub, @Nullable Level contextLevel) {
        StraddleMapping mapping = getMappingInto(sub, contextLevel);
        return mapping == null ? null : mapping.blockOffsetOrNull();
    }

    /**
     * Whether {@code sub} is currently straddling a portal at all, judged from
     * {@code contextLevel}'s side. Client: the straddle-portal finder (same source the
     * renderer and collision mapping use). Server: an active terrain-clone session.
     */
    public static boolean isStraddling(@Nullable SubLevel sub, @Nullable Level contextLevel) {
        if (sub == null || contextLevel == null) return false;
        if (!IplDimAgnostic.isHosted(sub)) return false;
        if (contextLevel.isClientSide()) {
            return ipl.sable.client.IplClientHostedLookup.isClientStraddling(sub);
        }
        return IplStraddleCloneBody.hasSession(sub.getUniqueId())
            || IplStraddleTerrainClone.hasSession(sub.getUniqueId());
    }

    /** One straddle image's frame: the portal (null for legacy terrain-clone
     *  translations) and its isometry. */
    public record StraddleFrame(
        @Nullable qouteall.imm_ptl.core.portal.Portal portal, StraddleMapping mapping
    ) {}

    /** Visit every straddle of {@code sub} mapping INTO {@code ctx} (dest side). */
    public static void forEachStraddleInto(
        SubLevel sub, Level ctx,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal, StraddleMapping> visitor
    ) {
        if (sub == null || ctx == null || !IplDimAgnostic.isHosted(sub)) return;
        if (ctx.isClientSide()) {
            ipl.sable.client.IplClientHostedLookup.forEachClientStraddleInto(sub, ctx, visitor);
            return;
        }
        IplStraddleCloneBody.forEachSessionInto(sub, ctx, visitor);
        BlockPos terrainOffset = IplStraddleTerrainClone.getOffsetInto(sub, ctx);
        if (terrainOffset != null) {
            visitor.accept(null, StraddleMapping.ofTranslation(terrainOffset));
        }
    }

    /**
     * Atlas pick fix: a world point that lies on a straddling ship's THROUGH half
     * (past the portal plane, inside the aperture column — the physics clip's cut)
     * physically exists at its MAPPED position. Remaps such points; returns null when
     * the point is on the source half (caller keeps the unmapped projection).
     * Multi-straddle: the first session whose cut contains the point wins.
     *
     * <p>This is what makes plot-space pick hits SURVIVE vanilla's reach clamp:
     * distance checks project the hit out of the sub level, and the through half's
     * truthful world position is the mapped one.
     */
    @Nullable
    public static Vec3 remapThroughHalfProjection(SubLevel sub, Level ctx, Vec3 world) {
        if (sub == null || ctx == null || world == null) return null;
        Vec3[] result = {null};
        forEachStraddleFrom(sub, ctx, (portal, mapping) -> {
            if (result[0] != null || portal == null || mapping == null) return;
            Vec3 origin = portal.getOriginPos();
            Vec3 n = portal.getNormal(); // source side keeps +normal (keep-filter convention)
            double d = (world.x - origin.x) * n.x + (world.y - origin.y) * n.y
                + (world.z - origin.z) * n.z;
            if (d >= 0.0) return; // source half
            Vec3 w = portal.getAxisW();
            Vec3 h = portal.getAxisH();
            double lw = (world.x - origin.x) * w.x + (world.y - origin.y) * w.y
                + (world.z - origin.z) * w.z;
            if (Math.abs(lw) > portal.getWidth() * 0.5 + APERTURE_CLIP_MARGIN) return;
            double lh = (world.x - origin.x) * h.x + (world.y - origin.y) * h.y
                + (world.z - origin.z) * h.z;
            if (Math.abs(lh) > portal.getHeight() * 0.5 + APERTURE_CLIP_MARGIN) return;
            result[0] = mapping.mapPoint(world);
        });
        return result[0];
    }

    /** Visit every straddle of {@code sub} exiting FROM {@code ctx} (its parent side). */
    public static void forEachStraddleFrom(
        SubLevel sub, Level ctx,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal, StraddleMapping> visitor
    ) {
        if (sub == null || ctx == null || !IplDimAgnostic.isHosted(sub)) return;
        if (ctx.isClientSide()) {
            ipl.sable.client.IplClientHostedLookup.forEachClientStraddleFrom(sub, ctx, visitor);
            return;
        }
        IplStraddleCloneBody.forEachSessionFrom(sub, ctx, visitor);
    }

    /**
     * The interaction frame for a position: null = the native/source frame; otherwise
     * the (portal, mapping) of the NEAREST mapped image. Multi-straddle-aware: every
     * session's image competes, each measured against its plane-CLIPPED half box (the
     * half that physically exists), and the native candidate is the source box clipped
     * by EVERY session's cut. Ties resolve to the mapped side (legacy behavior).
     */
    @Nullable
    public static StraddleFrame chooseCollisionFrame(SubLevel sub, Level ctx, Vec3 position) {
        if (sub == null || ctx == null || !IplDimAgnostic.isHosted(sub)) return null;

        dev.ryanhcode.sable.companion.math.BoundingBox3dc shipBox = sub.boundingBox();
        boolean nativeHere = IplDimAgnostic.getParentLevel(sub) == ctx;

        double nativeDist = Double.MAX_VALUE;
        if (nativeHere) {
            dev.ryanhcode.sable.companion.math.BoundingBox3d src =
                new dev.ryanhcode.sable.companion.math.BoundingBox3d();
            src.set(shipBox);
            dev.ryanhcode.sable.companion.math.BoundingBox3d[] srcRef = {src};
            forEachStraddleFrom(sub, ctx, (portal, mapping) -> {
                if (srcRef[0] == null || portal == null) return;
                srcRef[0] = clipBoxKeeping(srcRef[0], portal.getOriginPos(), portal.getNormal());
            });
            nativeDist = srcRef[0] == null
                ? Double.MAX_VALUE : distanceSqToBox(srcRef[0], position);
        }

        double[] best = {nativeDist};
        StraddleFrame[] chosen = {null};
        boolean nativeHereFinal = nativeHere;
        forEachStraddleInto(sub, ctx, (portal, mapping) -> {
            double d;
            if (portal != null) {
                Vec3 destPlanePoint = mapping.mapPoint(portal.getOriginPos());
                Vec3 destInward = mapping.mapVec(portal.getNormal().scale(-1.0));
                // Same-dimension: the image only claims positions past ITS dest plane.
                if (nativeHereFinal
                    && destInward.dot(position.subtract(destPlanePoint)) < 0.0) {
                    return;
                }
                dev.ryanhcode.sable.companion.math.BoundingBox3d img =
                    clipBoxKeeping(mapping.mapAabb(shipBox), destPlanePoint, destInward);
                d = img == null ? Double.MAX_VALUE : distanceSqToBox(img, position);
            } else {
                d = distanceSqToBox(mapping.mapAabb(shipBox), position);
            }
            if (d <= best[0]) {
                best[0] = d;
                chosen[0] = new StraddleFrame(portal, mapping);
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

    /** Legacy BlockPos view of {@link #getCollisionMappingInto}. */
    @Nullable
    public static BlockPos getCollisionOffsetInto(
        @Nullable SubLevel sub, @Nullable Level contextLevel, AABB entityBounds
    ) {
        StraddleMapping mapping = getCollisionMappingInto(sub, contextLevel, entityBounds);
        return mapping == null ? null : mapping.blockOffsetOrNull();
    }

    /** Destination mapping even when source and destination share the same Level. */
    @Nullable
    public static StraddleMapping getStraddleDestinationMapping(
        @Nullable SubLevel sub, @Nullable Level contextLevel
    ) {
        if (sub == null || contextLevel == null || !IplDimAgnostic.isHosted(sub)) return null;
        if (contextLevel.isClientSide()) {
            // Client-only class; loaded lazily when this branch executes.
            return ipl.sable.client.IplClientHostedLookup.getClientStraddleMappingInto(sub, contextLevel);
        }
        StraddleMapping cloneMapping = IplStraddleCloneBody.getMappingInto(sub, contextLevel);
        if (cloneMapping != null) return cloneMapping;
        BlockPos terrainOffset = IplStraddleTerrainClone.getOffsetInto(sub, contextLevel);
        return terrainOffset == null ? null : StraddleMapping.ofTranslation(terrainOffset);
    }

    /** Legacy BlockPos view of {@link #getStraddleDestinationMapping}. */
    @Nullable
    public static BlockPos getStraddleDestinationOffset(
        @Nullable SubLevel sub, @Nullable Level contextLevel
    ) {
        StraddleMapping mapping = getStraddleDestinationMapping(sub, contextLevel);
        return mapping == null ? null : mapping.blockOffsetOrNull();
    }

    /**
     * Lateral slack around the aperture rectangle for {@link #getBlockCollisionKeepFilter}:
     * block CENTERS of edge blocks flush with the portal frame sit up to half a block
     * outside the exact aperture. Over-covering is safe — ship geometry past the plane
     * outside the aperture column cannot exist mid-crossing (it would have been blocked).
     */
    private static final double APERTURE_CLIP_MARGIN = 0.5;

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
        Double.parseDouble(System.getProperty("ipl.sable.clip.seamSupportMargin", "0.9"));

    /**
     * Plot-local block keep-filter for entity-vs-ship collision while the ship straddles
     * a portal, or null when no clipping applies. Selects the frame the collision code
     * uses for this entity (the chosen image's mapped filter, or the AND of every
     * source-side cut) — both from the same {@link #chooseCollisionFrame} the pose
     * wraps use, so filter and pose can never disagree. Legacy terrain-clone sessions
     * expose no portal snapshot and keep full collision (pre-existing behavior).
     */
    @Nullable
    public static java.util.function.Predicate<BlockPos> getBlockCollisionKeepFilter(
        @Nullable SubLevel sub, @Nullable Level contextLevel, @Nullable AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;
        if (!IplDimAgnostic.isHosted(sub)) return null;
        StraddleFrame frame = chooseCollisionFrame(sub, contextLevel, entityBounds.getCenter());
        if (frame != null) {
            if (frame.portal() == null) return null; // terrain-clone: no aperture data
            return buildKeepFilter(
                frame.mapping().mapPose(sub.logicalPose()),
                frame.mapping().mapPoint(frame.portal().getOriginPos()),
                frame.mapping().mapVec(frame.portal().getNormal().scale(-1.0)),
                SEAM_SUPPORT_MARGIN,
                null, null, 0.0, 0.0);
        }
        return getSourceHalfKeepFilter(sub, contextLevel, SEAM_SUPPORT_MARGIN);
    }

    /**
     * Keep-filter for plot blocks reached through the ship's NATIVE frame (real pose):
     * drops the through-half — past the portal plane, inside the aperture column only.
     * A ship part reaching past the plane's lateral extension BESIDE the portal frame is
     * still physically on this side and stays. This is the gameplay counterpart of the
     * native aperture contact clip (spec §2.5). Null when the ship isn't straddling out
     * of {@code contextLevel}.
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
        if (sub == null || contextLevel == null || !IplDimAgnostic.isHosted(sub)) return null;
        if (IplDimAgnostic.getParentLevel(sub) != contextLevel) return null;

        // Source side keeps +portal normal (crossing direction is -normal, the same
        // convention as isInMappedHalf and the transit controller's parity rule).
        Pose3dc pose = sub.logicalPose();
        java.util.List<java.util.function.Predicate<BlockPos>> parts = new java.util.ArrayList<>(2);
        forEachStraddleFrom(sub, contextLevel, (portal, mapping) -> {
            if (portal == null) return;
            parts.add(buildKeepFilter(
                pose,
                portal.getOriginPos(), portal.getNormal(), planeMargin,
                portal.getAxisW(), portal.getAxisH(),
                portal.getWidth() * 0.5 + APERTURE_CLIP_MARGIN,
                portal.getHeight() * 0.5 + APERTURE_CLIP_MARGIN));
        });
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        return pos -> {
            for (java.util.function.Predicate<BlockPos> part : parts) {
                if (!part.test(pos)) return false;
            }
            return true;
        };
    }

    /**
     * Compile a world-frame half-space cut (optionally aperture-column-bounded) into a
     * plot-local block-center predicate: transformed once, three dot products per block.
     * Uses the end-of-tick pose rather than any substep lerp — within-tick ship motion
     * is far below the block-center granularity this test operates at.
     */
    private static java.util.function.Predicate<BlockPos> buildKeepFilter(
        Pose3dc pose, Vec3 point, Vec3 keepNormal, double planeMargin,
        @Nullable Vec3 axisW, @Nullable Vec3 axisH, double halfW, double halfH
    ) {
        org.joml.Vector3d lp = pose.transformPositionInverse(
            new org.joml.Vector3d(point.x, point.y, point.z));
        org.joml.Vector3d ln = pose.transformNormalInverse(
            new org.joml.Vector3d(keepNormal.x, keepNormal.y, keepNormal.z),
            new org.joml.Vector3d());
        double keepThreshold = -planeMargin;
        if (axisW == null || axisH == null) {
            return pos -> (pos.getX() + 0.5 - lp.x) * ln.x
                        + (pos.getY() + 0.5 - lp.y) * ln.y
                        + (pos.getZ() + 0.5 - lp.z) * ln.z >= keepThreshold;
        }
        org.joml.Vector3d lw = pose.transformNormalInverse(
            new org.joml.Vector3d(axisW.x, axisW.y, axisW.z), new org.joml.Vector3d());
        org.joml.Vector3d lh = pose.transformNormalInverse(
            new org.joml.Vector3d(axisH.x, axisH.y, axisH.z), new org.joml.Vector3d());
        return pos -> {
            double px = pos.getX() + 0.5 - lp.x;
            double py = pos.getY() + 0.5 - lp.y;
            double pz = pos.getZ() + 0.5 - lp.z;
            double d = px * ln.x + py * ln.y + pz * ln.z;
            if (d >= keepThreshold) return true;
            double w = Math.abs(px * lw.x + py * lw.y + pz * lw.z);
            double h = Math.abs(px * lh.x + py * lh.y + pz * lh.z);
            return w > halfW || h > halfH;
        };
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

    /** Copy of {@code pose} translated into the mapped frame. */
    public static Pose3d mapped(Pose3dc pose, BlockPos offset) {
        Pose3d out = new Pose3d(pose);
        out.position().add(offset.getX(), offset.getY(), offset.getZ());
        return out;
    }

    public static boolean isApproxIdentity(@Nullable DQuaternion q) {
        if (q == null) return true;
        return Math.abs(Math.abs(q.w) - 1.0) < 1e-4
            && Math.abs(q.x) < 1e-4 && Math.abs(q.y) < 1e-4 && Math.abs(q.z) < 1e-4;
    }

    @Nullable
    public static BlockPos blockAligned(Vec3 d) {
        long rx = Math.round(d.x), ry = Math.round(d.y), rz = Math.round(d.z);
        if (Math.abs(d.x - rx) > 0.01 || Math.abs(d.y - ry) > 0.01 || Math.abs(d.z - rz) > 0.01) {
            return null;
        }
        return new BlockPos((int) rx, (int) ry, (int) rz);
    }
}
