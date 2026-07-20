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

    /**
     * Entity-collision variant of {@link #getMappingInto}. Same-dimension crossings have
     * one Level for both source and destination, so the normal frame lookup deliberately
     * returns null. Collision still needs the mapped pose when the entity is standing in
     * the destination-side copy of that same scene.
     */
    @Nullable
    public static StraddleMapping getCollisionMappingInto(
        @Nullable SubLevel sub, @Nullable Level contextLevel, AABB entityBounds
    ) {
        if (sub == null || contextLevel == null || entityBounds == null) return null;

        StraddleMapping mapping = getStraddleDestinationMapping(sub, contextLevel);
        if (mapping == null) return null;
        if (IplDimAgnostic.getParentLevel(sub) != contextLevel) return mapping;
        return isInMappedCollisionHalf(sub, contextLevel, entityBounds.getCenter())
            ? mapping : null;
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

    /** Select the same-dimension source or mapped pose by the crossing plane, not AABB overlap. */
    private static boolean isInMappedCollisionHalf(SubLevel sub, Level contextLevel, Vec3 position) {
        if (contextLevel.isClientSide()) {
            if (!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub)) return false;
            ipl.sable.render.SourceClipPortalFinder.ClipDecision decision =
                ipl.sable.render.SourceClipPortalFinder.findStraddlingPortalPlane(clientSub);
            if (decision == null || decision.portal() == null) return false;
            qouteall.imm_ptl.core.portal.Portal portal = decision.portal();
            return isInMappedHalf(
                StraddleMapping.of(portal), portal, clientSub.boundingBox(), position);
        }
        return IplStraddleCloneBody.isInMappedHalf(sub, contextLevel, position);
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
        Vec3 destPlanePoint = mapping.mapPoint(portal.getOriginPos());
        Vec3 destInward = mapping.mapVec(portal.getNormal().scale(-1.0));
        if (destInward.dot(position.subtract(destPlanePoint)) < 0.0) return false;

        double dMapped = distanceSqToBox(mapping.mapAabb(shipBox), position);
        double dSource = distanceSqToBox(shipBox, position);
        return dMapped <= dSource;
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
