package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.util.LevelAccelerator;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2-3 of the per-scene portal physics (spec §2.3-§2.4): the straddle CLONE BODY.
 *
 * <p>While a hosted ship straddles a portal, a second native Rapier body — same voxel
 * colliders, mirrored mass — lives in the DESTINATION dimension's scene at the
 * portal-mapped pose. It is dynamic and velocity-SERVOED to the real body each substep
 * (a naive kinematic clone would present infinite mass to far-side contacts), and the
 * contact impulses it accumulates are read off the destination scene's contact-event
 * buffer and applied to the real body through the (inverse) portal isometry. The through-
 * part therefore collides with real dest terrain and real dest-side ships, with one-step
 * coupling lag — replacing the phantom terrain clone ({@link IplStraddleTerrainClone}).
 *
 * <p>The clone is PURE NATIVE STATE: a fresh body id from {@link Rapier3D#nextBodyID()},
 * never registered in any Java pipeline's {@code activeSubLevels} — invisible to the
 * ownership guard, Sable bookkeeping, and gameplay. Collider handles are valid across
 * scenes (the native voxel-collider registry is global), so the bake reuses the parent
 * pipeline's bakery and accelerator (under the hosting read override, where plot content
 * physically lives).
 *
 * <p>Scope (like the terrain clone): translation-only, scale-1, block-aligned portal
 * pairs. Known limitations of this first cut: clone colliders are not clipped at the
 * portal plane (the not-yet-through hull part can touch dest-side geometry behind the
 * aperture — spec §2.5, phase 4 native work), feedback runs on pair-level force scalars
 * (no tangent/friction component until the readout fidelity patch), and block changes
 * mid-straddle do not update the clone's colliders until the next straddle session.
 *
 * <p>Kill switch: {@code -Dipl.sable.cloneBodies=false} reverts to the phantom terrain
 * clone.
 */
public final class IplStraddleCloneBody {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-straddle-clone");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.cloneBodies", "true"));

    // ------------------------------------------------------------------
    // Position-based coupling (replaces impulse feedback): the clone is PINNED to the
    // portal-mapped pose before each dest-scene substep; the solver then resolves its
    // contacts — positionally and with correct torques, because the contact points act on
    // the clone's real collider — and whatever pose/velocity correction the solver applied
    // to the clone is copied onto the real body after the step. Per-substep transfer caps
    // keep deep-penetration recovery from teleporting the ship.
    // ------------------------------------------------------------------

    /** Ignore tiny solver position corrections that otherwise cause visible pose churn. */
    private static final double TRANSFER_DEADBAND =
        Double.parseDouble(System.getProperty("ipl.sable.clone.transferDeadband", "0.01"));
    /** Max position correction copied to the real body per substep (blocks). */
    private static final double MAX_POS_TRANSFER =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxPosTransfer", "0.5"));
    /** Max rotation correction copied per substep (radians). */
    private static final double MAX_ANG_TRANSFER =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxAngTransfer", "0.15"));
    /** Max velocity correction copied per substep (m/s). */
    private static final double MAX_VEL_TRANSFER =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxVelTransfer", "3.0"));

    private static final Map<MirrorRegistry.MirrorKey, Session> SESSIONS = new HashMap<>();

    private static boolean loggedSkip = false;
    private static long lastFeedbackLogMs = 0;
    private static long lastServoLogMs = 0;
    private static long lastTeeLogMs = 0;

    private IplStraddleCloneBody() {}

    private static final class Session {
        final ServerSubLevel sub;
        final ServerLevel parent;
        final ServerLevel dest;
        /** Sable 2.0: scenes are native long handles held by each level's pipeline. */
        final long parentScene;
        final long destScene;
        final int realId;
        final int cloneId;
        final BlockPos offset;
        /** The REAL body's aperture clip region for this portal (14 doubles), if natives allow. */
        double[] realClipRegion;
        final LongArrayList fedSections = new LongArrayList();
        final double[] poseBuf = new double[7];
        final double[] realLin = new double[3];
        final double[] realAng = new double[3];
        final double[] cloneLin = new double[3];
        final double[] cloneAng = new double[3];
        /** Pre-step pin state, for measuring the solver's post-step correction. */
        final double[] targetPose = new double[7];
        final double[] pinnedLin = new double[3];
        final double[] pinnedAng = new double[3];
        boolean pinned = false;
        /** Dest dimension gravity (m/s²), analytically removed from the velocity delta. */
        org.joml.Vector3d destGravity = new org.joml.Vector3d(0, -9.8, 0);

        Session(ServerSubLevel sub, ServerLevel parent, ServerLevel dest, BlockPos offset,
                long parentScene, long destScene) {
            this.sub = sub;
            this.parent = parent;
            this.dest = dest;
            this.offset = offset;
            this.parentScene = parentScene;
            this.destScene = destScene;
            this.realId = Rapier3D.getID(sub);
            this.cloneId = Rapier3D.nextBodyID();
        }
    }

    public static boolean isEnabled() {
        return ENABLED && IplSceneOwnership.isEnabled();
    }

    /** Called each tick a hosted ship is STRADDLING (from the transit controller). */
    public static void onStraddleTick(ServerSubLevel hosted, Portal portal, Vec3 sourceToDest) {
        if (!isEnabled() || !IplDimAgnostic.isHosted(hosted)) return;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(hosted);
        if (parent == null) return;
        ServerLevel dest = parent.getServer().getLevel(portal.getDestDim());
        if (dest == null || IplDimAgnostic.isHostingLevel(dest)) return;

        // Translation-only, scale-1, block-aligned gates (same envelope as the terrain clone).
        if (!IplStraddlePoseMap.isApproxIdentity(portal.getRotationD())
            || Math.abs(portal.getScaling() - 1.0) > 1e-9) {
            if (!loggedSkip) {
                loggedSkip = true;
                LOG.info("[IPL-CLONE] portal {} has rotation/scale; clone bodies support "
                    + "translation-only scale-1 pairs — skipping (logged once)", portal.getUUID());
            }
            return;
        }
        BlockPos offset = IplStraddlePoseMap.blockAligned(
            portal.getDestPos().subtract(portal.getOriginPos()));
        if (offset == null) return;

        // The real body must actually be in the parent scene — raw native reads against a
        // scene that doesn't hold the body are the hang/abort class the ownership guard
        // exists to prevent. (Body lands in the parent scene via phase 1's routing; a
        // boot-fallback body still in the hosting scene gets reconciled within a tick.)
        if (IplSceneOwnership.getBodyHome(hosted) != parent) return;

        MirrorRegistry.MirrorKey key =
            new MirrorRegistry.MirrorKey(hosted.getUniqueId(), portal.getUUID());
        if (SESSIONS.containsKey(key)) return;

        RapierPhysicsPipeline parentPipeline = IplSceneOwnership.pipelineOf(parent);
        RapierPhysicsPipeline destPipeline = IplSceneOwnership.pipelineOf(dest);
        if (parentPipeline == null || destPipeline == null) return;
        long parentScene = ((IplRapierPipelineAccess) parentPipeline).ipl$sceneHandle();
        long destScene = ((IplRapierPipelineAccess) destPipeline).ipl$sceneHandle();

        Session session = new Session(hosted, parent, dest, offset, parentScene, destScene);
        try {
            session.destGravity.set(
                dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                    .getGravity(dest));
        } catch (Throwable t) {
            // keep the (0, -9.8, 0) default
        }
        if (!spawnClone(session)) return;
        SESSIONS.put(key, session);

        // Aperture contact clipping (spec §2.5), when the IPSable natives are live:
        //  - REAL body: contacts past the portal plane inside the aperture are dropped —
        //    the through-part stops colliding with SOURCE-side terrain and ships.
        //  - CLONE body: the complementary half — contacts BEFORE the (offset-mapped)
        //    plane are dropped, so only the through-part is physically present dest-side.
        if (ipl.sable.natives.IplRapierNatives.isAvailable()) {
            double margin = 1.0;
            double halfW = portal.getWidth() * 0.5 + margin;
            double halfH = portal.getHeight() * 0.5 + margin;
            Vec3 origin = portal.getOriginPos();
            Vec3 axisW = portal.getAxisW();
            Vec3 axisH = portal.getAxisH();

            session.realClipRegion = clipRegion(origin, sourceToDest, axisW, halfW, axisH, halfH);
            applyRealClipRegions(hosted, session.parentScene, session.realId);

            Vec3 destPlanePoint = origin.add(offset.getX(), offset.getY(), offset.getZ());
            double[] cloneRegion = clipRegion(
                destPlanePoint, sourceToDest.scale(-1.0), axisW, halfW, axisH, halfH);
            ipl.sable.natives.IplRapierNatives.setClipRegions(
                session.destScene, session.cloneId, cloneRegion);
        }

        LOG.info("[IPL-CLONE] start uuid={} portal={} offset={} cloneId={} destScene={} clipped={}",
            hosted.getUniqueId(), portal.getUUID(), offset, session.cloneId, session.destScene,
            session.realClipRegion != null);
    }

    private static double[] clipRegion(
        Vec3 point, Vec3 normal, Vec3 axisW, double halfW, Vec3 axisH, double halfH
    ) {
        return new double[]{
            point.x, point.y, point.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z, halfW,
            axisH.x, axisH.y, axisH.z, halfH
        };
    }

    /**
     * (Re)apply the union of all active sessions' clip regions to a ship's REAL body —
     * a ship can straddle several portals at once, and regions replace wholesale.
     */
    private static void applyRealClipRegions(ServerSubLevel ship, long parentScene, int realId) {
        java.util.ArrayList<double[]> regions = new java.util.ArrayList<>(2);
        for (Session s : SESSIONS.values()) {
            if (s.sub == ship && s.realClipRegion != null) regions.add(s.realClipRegion);
        }
        double[] flat = new double[regions.size() * 14];
        for (int i = 0; i < regions.size(); i++) {
            System.arraycopy(regions.get(i), 0, flat, i * 14, 14);
        }
        ipl.sable.natives.IplRapierNatives.setClipRegions(parentScene, realId, flat);
    }

    /** Straddle ended (backed out, flipped, or left the zone): despawn the clone. */
    public static void clear(MirrorRegistry.MirrorKey key, String reason) {
        Session session = SESSIONS.remove(key);
        if (session == null) return;
        despawn(session);
        // Recompute the real body's clip regions from whatever sessions remain (usually
        // none → cleared): the through-part becomes fully source-solid again.
        if (session.realClipRegion != null
            && ipl.sable.natives.IplRapierNatives.isAvailable()
            && !session.sub.isRemoved()) {
            applyRealClipRegions(session.sub, session.parentScene, session.realId);
        }
        LOG.info("[IPL-CLONE] end uuid={} cloneId={} reason={}",
            session.sub.getUniqueId(), session.cloneId, reason);
    }

    /** Server stopping: drop all state without native despawns (scenes die with the JVM). */
    public static void clearAll() {
        SESSIONS.clear();
        loggedSkip = false;
    }

    /** Whether any clone session is active for this ship (server-side straddle truth). */
    public static boolean hasSession(UUID shipUuid) {
        for (Session s : SESSIONS.values()) {
            if (s.sub.getUniqueId().equals(shipUuid)) return true;
        }
        return false;
    }

    /**
     * The mapping offset of an active clone session of {@code sub} into {@code destLevel},
     * or null. Feeds the frame mapping for entity collision/interaction on the through-part
     * (same contract as {@code IplStraddleTerrainClone.getOffsetInto}).
     */
    public static BlockPos getOffsetInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level destLevel
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.offset;
            }
        }
        return null;
    }

    /**
     * Visit every active clone session whose DESTINATION is {@code destLevel}: the ship and
     * the source→dest block offset. Used by the ticket enrollment — the dest scene must hold
     * terrain around the CLONE's region (ship bounds ⊕ offset), which no stock path covers:
     * the ticket manager only enrolls around ships whose parent is that level, and the clone
     * is pure native state.
     */
    public static void forEachSessionInto(
        ServerLevel destLevel,
        java.util.function.BiConsumer<ServerSubLevel, BlockPos> visitor
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && !s.sub.isRemoved()) {
                visitor.accept(s.sub, s.offset);
            }
        }
    }

    // ------------------------------------------------------------------
    // Spawn / despawn.
    // ------------------------------------------------------------------

    private static boolean spawnClone(Session s) {
        RapierPhysicsPipeline parentPipeline = IplSceneOwnership.pipelineOf(s.parent);
        if (parentPipeline == null) return false;

        Pose3dc pose = s.sub.logicalPose();
        double[] p7 = {
            pose.position().x() + s.offset.getX(),
            pose.position().y() + s.offset.getY(),
            pose.position().z() + s.offset.getZ(),
            pose.orientation().x(), pose.orientation().y(),
            pose.orientation().z(), pose.orientation().w()
        };
        ipl.sable.mixin.IplRapier3DInvoker.ipl$createSubLevel(s.destScene, s.cloneId, p7);

        // Stats BEFORE any chunk insert — native insert_block unwraps local_bounds and
        // hard-aborts the process without them (the phase-1 lesson, lib.rs:218).
        Vector3dc com = s.sub.getMassTracker().getCenterOfMass();
        if (com != null) {
            ipl.sable.mixin.IplRapier3DInvoker.ipl$setCenterOfMass(s.destScene, s.cloneId, com.x(), com.y(), com.z());
            ipl.sable.mixin.IplRapier3DInvoker.ipl$setMassPropertiesFrom(s.destScene, s.cloneId, s.sub.getMassTracker());
        }
        BoundingBox3ic bounds = s.sub.getPlot().getBoundingBox();
        ipl.sable.mixin.IplRapier3DInvoker.ipl$setLocalBounds(s.destScene, s.cloneId,
            bounds.minX(), bounds.minY(), bounds.minZ(),
            bounds.maxX(), bounds.maxY(), bounds.maxZ());

        feedCloneChunks(s, parentPipeline);
        return true;
    }

    /**
     * Bake the ship's plot voxels and feed them to the clone in the dest scene. Reuses the
     * PARENT pipeline's accelerator + bakery (collider handles are valid in every scene —
     * the native registry is global) under the hosting read override (plot chunks
     * physically live in the hosting level).
     */
    private static void feedCloneChunks(Session s, RapierPhysicsPipeline parentPipeline) {
        IplRapierPipelineAccess access = (IplRapierPipelineAccess) parentPipeline;
        LevelAccelerator accel = access.ipl$accelerator();
        var bakery = access.ipl$colliderBakery();

        IplTerrainReadOverride.set(s.sub.getLevel());
        try {
            accel.clearCache();
            int[] packed = new int[LevelChunkSection.SECTION_SIZE];
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (PlotChunkHolder holder : s.sub.getPlot().getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                ChunkPos cp = chunk.getPos();
                LevelChunk accelChunk = accel.getChunk(cp.x, cp.z);
                LevelChunkSection[] sections = chunk.getSections();

                for (int i = 0; i < chunk.getSectionsCount(); i++) {
                    if (sections[i].hasOnlyAir()) continue;
                    int sectionY = chunk.getSectionYFromSectionIndex(i);
                    int minX = cp.getMinBlockX(), minY = sectionY << 4, minZ = cp.getMinBlockZ();

                    java.util.Arrays.fill(packed, 0);
                    for (int bx = 0; bx < 16; bx++) {
                        for (int bz = 0; bz < 16; bz++) {
                            for (int by = 0; by < 16; by++) {
                                cursor.set(minX + bx, minY + by, minZ + bz);
                                VoxelNeighborhoodState state =
                                    VoxelNeighborhoodState.getState(accel, cursor, accelChunk);
                                RapierVoxelColliderData data = bakery.getPhysicsDataForBlock(
                                    accel.getBlockState(accelChunk, cursor));
                                int colliderValue = data == null ? 0 : data.handle() + 1;
                                packed[bx + (bz << 4) + (by << 8)] =
                                    ((int) state.byteRepresentation()) | (colliderValue << 16);
                            }
                        }
                    }
                    ipl.sable.mixin.IplRapier3DInvoker.ipl$addChunk(s.destScene, cp.x, sectionY, cp.z, packed, false, s.cloneId);
                    s.fedSections.add(SectionPos.asLong(cp.x, sectionY, cp.z));
                }
            }
        } finally {
            IplTerrainReadOverride.clear();
        }
    }

    private static void despawn(Session s) {
        for (int i = 0; i < s.fedSections.size(); i++) {
            long packed = s.fedSections.getLong(i);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$removeChunk(s.destScene,
                SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed), false);
        }
        ipl.sable.mixin.IplRapier3DInvoker.ipl$removeSubLevel(s.destScene, s.cloneId);
    }

    // ------------------------------------------------------------------
    // Position-based coupling: pin before the substep, measure after it.
    // ------------------------------------------------------------------

    /** Pin every clone whose DEST scene is about to step to the portal-mapped pose. */
    public static void preStep(ServerLevel steppingLevel, double dt) {
        if (SESSIONS.isEmpty()) return;

        for (Session s : SESSIONS.values()) {
            if (s.dest != steppingLevel) continue;
            if (s.sub.isRemoved()) {
                s.pinned = false;
                continue;
            }

            Pose3dc real = s.sub.logicalPose();
            s.targetPose[0] = real.position().x() + s.offset.getX();
            s.targetPose[1] = real.position().y() + s.offset.getY();
            s.targetPose[2] = real.position().z() + s.offset.getZ();
            s.targetPose[3] = real.orientation().x();
            s.targetPose[4] = real.orientation().y();
            s.targetPose[5] = real.orientation().z();
            s.targetPose[6] = real.orientation().w();

            ipl.sable.mixin.IplRapier3DInvoker.ipl$teleportObject(s.destScene, s.cloneId,
                s.targetPose[0], s.targetPose[1], s.targetPose[2],
                s.targetPose[3], s.targetPose[4], s.targetPose[5], s.targetPose[6]);

            // Exact-set the clone's velocities to the real body's (translation-only
            // portals: the frames align; addLinearAngularVelocities is a native set of
            // current + delta).
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getLinearVelocity(s.parentScene, s.realId, s.realLin);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getAngularVelocity(s.parentScene, s.realId, s.realAng);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getLinearVelocity(s.destScene, s.cloneId, s.cloneLin);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getAngularVelocity(s.destScene, s.cloneId, s.cloneAng);
            Rapier3D.addLinearAngularVelocities(s.destScene, s.cloneId,
                s.realLin[0] - s.cloneLin[0], s.realLin[1] - s.cloneLin[1],
                s.realLin[2] - s.cloneLin[2],
                s.realAng[0] - s.cloneAng[0], s.realAng[1] - s.cloneAng[1],
                s.realAng[2] - s.cloneAng[2], true);

            s.pinnedLin[0] = s.realLin[0];
            s.pinnedLin[1] = s.realLin[1];
            s.pinnedLin[2] = s.realLin[2];
            s.pinnedAng[0] = s.realAng[0];
            s.pinnedAng[1] = s.realAng[1];
            s.pinnedAng[2] = s.realAng[2];
            s.pinned = true;
        }
    }

    /**
     * After the dest scene stepped, transfer its solver correction to the real body. Gravity's
     * free-fall contribution is removed so a contact-free clone transfers nothing.
     */
    public static void postStep(ServerLevel steppingLevel, double dt) {
        if (SESSIONS.isEmpty()) return;

        for (Session s : SESSIONS.values()) {
            if (s.dest != steppingLevel || !s.pinned) continue;
            s.pinned = false;
            if (s.sub.isRemoved()) continue;

            Rapier3D.getPose(s.destScene, s.cloneId, s.poseBuf);
            double dx = s.poseBuf[0] - s.targetPose[0] - s.destGravity.x * dt * dt * 0.5;
            double dy = s.poseBuf[1] - s.targetPose[1] - s.destGravity.y * dt * dt * 0.5;
            double dz = s.poseBuf[2] - s.targetPose[2] - s.destGravity.z * dt * dt * 0.5;
            // The pin gives the clone the real body's velocity; subtract free integration
            // to retain only the destination solver's contact correction.
            dx -= s.pinnedLin[0] * dt;
            dy -= s.pinnedLin[1] * dt;
            dz -= s.pinnedLin[2] * dt;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            Quaterniond target = new Quaterniond(
                s.targetPose[3], s.targetPose[4], s.targetPose[5], s.targetPose[6]);
            Quaterniond current = new Quaterniond(
                s.poseBuf[3], s.poseBuf[4], s.poseBuf[5], s.poseBuf[6]);
            double wLen = Math.sqrt(s.pinnedAng[0] * s.pinnedAng[0]
                + s.pinnedAng[1] * s.pinnedAng[1] + s.pinnedAng[2] * s.pinnedAng[2]);
            if (wLen > 1e-9) {
                Quaterniond freeSpin = new Quaterniond().rotationAxis(wLen * dt,
                    s.pinnedAng[0] / wLen, s.pinnedAng[1] / wLen, s.pinnedAng[2] / wLen);
                target = freeSpin.mul(target, new Quaterniond());
            }
            Quaterniond dq = current.mul(target.conjugate(new Quaterniond()), new Quaterniond());
            if (dq.w < 0) dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
            double axisLen = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
            double angle = axisLen > 1e-9
                ? 2.0 * Math.acos(Math.min(1.0, Math.max(-1.0, dq.w))) : 0.0;

            ipl.sable.mixin.IplRapier3DInvoker.ipl$getLinearVelocity(s.destScene, s.cloneId, s.cloneLin);
            ipl.sable.mixin.IplRapier3DInvoker.ipl$getAngularVelocity(s.destScene, s.cloneId, s.cloneAng);
            double dvx = s.cloneLin[0] - s.pinnedLin[0] - s.destGravity.x * dt;
            double dvy = s.cloneLin[1] - s.pinnedLin[1] - s.destGravity.y * dt;
            double dvz = s.cloneLin[2] - s.pinnedLin[2] - s.destGravity.z * dt;
            double dax = s.cloneAng[0] - s.pinnedAng[0];
            double day = s.cloneAng[1] - s.pinnedAng[1];
            double daz = s.cloneAng[2] - s.pinnedAng[2];
            double dvMag = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
            double daMag = Math.sqrt(dax * dax + day * day + daz * daz);

            boolean velMoved = dvMag > 0.05 || daMag > 0.02;
            boolean posMoved = dist > TRANSFER_DEADBAND;
            boolean rotMoved = angle > 0.01;
            if (!posMoved && !rotMoved && !velMoved) continue;

            if (dist > MAX_POS_TRANSFER) {
                double f = MAX_POS_TRANSFER / dist;
                dx *= f; dy *= f; dz *= f;
            }
            double appliedAngle = Math.min(angle, MAX_ANG_TRANSFER);
            if (dvMag > MAX_VEL_TRANSFER) {
                double f = MAX_VEL_TRANSFER / dvMag;
                dvx *= f; dvy *= f; dvz *= f;
            }
            if (daMag > MAX_VEL_TRANSFER) {
                double f = MAX_VEL_TRANSFER / daMag;
                dax *= f; day *= f; daz *= f;
            }

            // Apply clone contact response immediately, matching the original pre/post model.
            Rapier3D.getPose(s.parentScene, s.realId, s.poseBuf);
            double nx = s.poseBuf[0] + (posMoved ? dx : 0.0);
            double ny = s.poseBuf[1] + (posMoved ? dy : 0.0);
            double nz = s.poseBuf[2] + (posMoved ? dz : 0.0);
            Quaterniond realRot = new Quaterniond(
                s.poseBuf[3], s.poseBuf[4], s.poseBuf[5], s.poseBuf[6]);
            if (rotMoved && axisLen > 1e-9) {
                Quaterniond clampedDq = new Quaterniond().rotationAxis(appliedAngle,
                    dq.x / axisLen, dq.y / axisLen, dq.z / axisLen);
                realRot = clampedDq.mul(realRot, new Quaterniond()).normalize();
            }
            if (posMoved || rotMoved) {
                ipl.sable.mixin.IplRapier3DInvoker.ipl$teleportObject(s.parentScene, s.realId,
                    nx, ny, nz, realRot.x, realRot.y, realRot.z, realRot.w);
            }
            if (velMoved) {
                Rapier3D.addLinearAngularVelocities(s.parentScene, s.realId,
                    dvx, dvy, dvz, dax, day, daz, true);
            }
            Rapier3D.wakeUpObject(s.parentScene, s.realId);

            long now = System.currentTimeMillis();
            if (now - lastServoLogMs > 2000) {
                lastServoLogMs = now;
                LOG.info("[IPL-CLONE-PBC] cloneId={} dPos={} dAng={} dVel={}",
                    s.cloneId, String.format("%.3f", dist),
                    String.format("%.3f", angle), String.format("%.2f", dvMag));
            }
        }
    }

    // ------------------------------------------------------------------
    // Feedback: contact records teed off the dest pipeline's per-tick readout.
    // Record stride 15: [idA, idB, pairForce, normalA(3), normalB(3), pointA(3), pointB(3)]
    // — points/normals body-local (COM-relative), force a pair-level scalar repeated per
    // manifold point.
    // ------------------------------------------------------------------

    public static void onCollisionRecords(ServerLevel level, double[] records) {
        if (SESSIONS.isEmpty() || records == null) return;

        // Bring-up probe: prove the tee fires while a session targets this level, and show
        // what the dest scene's contact buffer contains (remove later).
        boolean anyHere = false;
        for (Session s : SESSIONS.values()) {
            if (s.dest == level) { anyHere = true; break; }
        }
        if (anyHere) {
            long now = System.currentTimeMillis();
            if (now - lastTeeLogMs > 2000) {
                lastTeeLogMs = now;
                StringBuilder ids = new StringBuilder();
                for (int i = 0; i + 15 <= records.length && i < 150; i += 15) {
                    ids.append('(').append((int) records[i]).append(',')
                        .append((int) records[i + 1]).append(") ");
                }
                LOG.info("[IPL-CLONE-TEE] dest={} records={} pairs: {}",
                    level.dimension().location(), records.length / 15, ids);
            }
        }
        // Contact impulses are no longer re-applied to the real body — the position-based
        // coupling (preStep/postStep) copies the solver's own resolution of the clone
        // instead, which carries correct torques by construction. The tee remains as a
        // diagnostic (which pairs are contacting dest-side).
    }
}
