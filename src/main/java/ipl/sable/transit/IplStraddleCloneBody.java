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

    /** Servo position-correction rate (1/s): velocity addend = error * rate. */
    private static final double POS_CORR_RATE =
        Double.parseDouble(System.getProperty("ipl.sable.clone.posCorrRate", "10.0"));
    private static final double ANG_CORR_RATE =
        Double.parseDouble(System.getProperty("ipl.sable.clone.angCorrRate", "10.0"));
    /** Pose divergence beyond which the clone snaps (teleport) instead of servoing. */
    private static final double SNAP_DISTANCE = 1.0;

    /** Feedback impulse = pairForce * dt * scale, dt folded in here (1/40 default stepping). */
    private static final double FEEDBACK_DT =
        Double.parseDouble(System.getProperty("ipl.sable.clone.feedbackDt", "0.025"));
    private static final double FEEDBACK_SCALE =
        Double.parseDouble(System.getProperty("ipl.sable.clone.feedbackScale", "1.0"));
    /** Flip feedback direction if the contact-normal sign convention turns out inverted. */
    private static final boolean FEEDBACK_FLIP =
        "true".equalsIgnoreCase(System.getProperty("ipl.sable.clone.feedbackFlip", "false"));
    /**
     * Hard cap on the velocity change feedback may apply to the real body per tick (m/s).
     * Penetration-recovery contact forces spike arbitrarily high while the servo holds the
     * clone against terrain (the real ship being pushed in by a drag motor or momentum);
     * uncapped, those spikes EJECT the ship from the seam. Capped, they saturate into firm
     * resistance.
     */
    private static final double MAX_FEEDBACK_DV =
        Double.parseDouble(System.getProperty("ipl.sable.clone.maxFeedbackDv", "3.0"));

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
        final int parentSceneId;
        final int destSceneId;
        final int realId;
        final int cloneId;
        final BlockPos offset;
        final LongArrayList fedSections = new LongArrayList();
        final double[] poseBuf = new double[7];
        final double[] realLin = new double[3];
        final double[] realAng = new double[3];
        final double[] cloneLin = new double[3];
        final double[] cloneAng = new double[3];

        Session(ServerSubLevel sub, ServerLevel parent, ServerLevel dest, BlockPos offset) {
            this.sub = sub;
            this.parent = parent;
            this.dest = dest;
            this.offset = offset;
            this.parentSceneId = Rapier3D.getID(parent);
            this.destSceneId = Rapier3D.getID(dest);
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

        Session session = new Session(hosted, parent, dest, offset);
        if (!spawnClone(session)) return;
        SESSIONS.put(key, session);
        LOG.info("[IPL-CLONE] start uuid={} portal={} offset={} cloneId={} destScene={}",
            hosted.getUniqueId(), portal.getUUID(), offset, session.cloneId, session.destSceneId);
    }

    /** Straddle ended (backed out, flipped, or left the zone): despawn the clone. */
    public static void clear(MirrorRegistry.MirrorKey key, String reason) {
        Session session = SESSIONS.remove(key);
        if (session == null) return;
        despawn(session);
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
        Rapier3D.createSubLevel(s.destSceneId, s.cloneId, p7);

        // Stats BEFORE any chunk insert — native insert_block unwraps local_bounds and
        // hard-aborts the process without them (the phase-1 lesson, lib.rs:218).
        Vector3dc com = s.sub.getMassTracker().getCenterOfMass();
        if (com != null) {
            Rapier3D.setCenterOfMass(s.destSceneId, s.cloneId, com.x(), com.y(), com.z());
            Rapier3D.setMassPropertiesFrom(s.destSceneId, s.cloneId, s.sub.getMassTracker());
        }
        BoundingBox3ic bounds = s.sub.getPlot().getBoundingBox();
        Rapier3D.setLocalBounds(s.destSceneId, s.cloneId,
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
                    Rapier3D.addChunk(s.destSceneId, cp.x, sectionY, cp.z, packed, false, s.cloneId);
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
            Rapier3D.removeChunk(s.destSceneId,
                SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed), false);
        }
        Rapier3D.removeSubLevel(s.destSceneId, s.cloneId);
    }

    // ------------------------------------------------------------------
    // Servo: called just before each dest scene's physics substep.
    // ------------------------------------------------------------------

    /** Drive every clone whose DEST scene is about to step toward the portal-mapped pose. */
    public static void servoPreStep(ServerLevel steppingLevel, double dt) {
        if (SESSIONS.isEmpty()) return;

        for (Session s : SESSIONS.values()) {
            if (s.dest != steppingLevel) continue;
            if (s.sub.isRemoved()) continue;

            Pose3dc real = s.sub.logicalPose();
            double tx = real.position().x() + s.offset.getX();
            double ty = real.position().y() + s.offset.getY();
            double tz = real.position().z() + s.offset.getZ();

            Rapier3D.getPose(s.destSceneId, s.cloneId, s.poseBuf);
            double ex = tx - s.poseBuf[0];
            double ey = ty - s.poseBuf[1];
            double ez = tz - s.poseBuf[2];
            double errSq = ex * ex + ey * ey + ez * ez;

            // Bring-up probe: prove the servo runs and show the chase error (remove later).
            long now = System.currentTimeMillis();
            if (now - lastServoLogMs > 2000) {
                lastServoLogMs = now;
                LOG.info("[IPL-CLONE-SERVO] cloneId={} dest={} posErr={} clonePos=({}, {}, {})",
                    s.cloneId, s.dest.dimension().location(),
                    String.format("%.3f", Math.sqrt(errSq)),
                    String.format("%.1f", s.poseBuf[0]), String.format("%.1f", s.poseBuf[1]),
                    String.format("%.1f", s.poseBuf[2]));
            }

            Rapier3D.getLinearVelocity(s.parentSceneId, s.realId, s.realLin);
            Rapier3D.getAngularVelocity(s.parentSceneId, s.realId, s.realAng);

            if (errSq > SNAP_DISTANCE * SNAP_DISTANCE) {
                Rapier3D.teleportObject(s.destSceneId, s.cloneId, tx, ty, tz,
                    real.orientation().x(), real.orientation().y(),
                    real.orientation().z(), real.orientation().w());
                Rapier3D.getLinearVelocity(s.destSceneId, s.cloneId, s.cloneLin);
                Rapier3D.getAngularVelocity(s.destSceneId, s.cloneId, s.cloneAng);
                Rapier3D.addLinearAngularVelocities(s.destSceneId, s.cloneId,
                    s.realLin[0] - s.cloneLin[0], s.realLin[1] - s.cloneLin[1],
                    s.realLin[2] - s.cloneLin[2],
                    s.realAng[0] - s.cloneAng[0], s.realAng[1] - s.cloneAng[1],
                    s.realAng[2] - s.cloneAng[2], true);
                continue;
            }

            // Orientation error → angular correction (shortest arc, axis * angle * rate).
            Quaterniond cloneQ = new Quaterniond(
                s.poseBuf[3], s.poseBuf[4], s.poseBuf[5], s.poseBuf[6]);
            Quaterniond targetQ = new Quaterniond(real.orientation());
            Quaterniond diff = targetQ.mul(cloneQ.conjugate(new Quaterniond()), new Quaterniond());
            if (diff.w < 0) diff.set(-diff.x, -diff.y, -diff.z, -diff.w);
            double cx = diff.x, cy = diff.y, cz = diff.z;
            double axisLen = Math.sqrt(cx * cx + cy * cy + cz * cz);
            double corrX = 0, corrY = 0, corrZ = 0;
            if (axisLen > 1e-9) {
                double angle = 2.0 * Math.acos(Math.min(1.0, Math.max(-1.0, diff.w)));
                double mul = angle * ANG_CORR_RATE / axisLen;
                corrX = cx * mul;
                corrY = cy * mul;
                corrZ = cz * mul;
            }

            double targetLinX = s.realLin[0] + ex * POS_CORR_RATE;
            double targetLinY = s.realLin[1] + ey * POS_CORR_RATE;
            double targetLinZ = s.realLin[2] + ez * POS_CORR_RATE;
            double targetAngX = s.realAng[0] + corrX;
            double targetAngY = s.realAng[1] + corrY;
            double targetAngZ = s.realAng[2] + corrZ;

            Rapier3D.getLinearVelocity(s.destSceneId, s.cloneId, s.cloneLin);
            Rapier3D.getAngularVelocity(s.destSceneId, s.cloneId, s.cloneAng);
            // addLinearAngularVelocities is set_linvel(current + delta) natively — adding
            // (target - current) is an exact set.
            Rapier3D.addLinearAngularVelocities(s.destSceneId, s.cloneId,
                targetLinX - s.cloneLin[0], targetLinY - s.cloneLin[1], targetLinZ - s.cloneLin[2],
                targetAngX - s.cloneAng[0], targetAngY - s.cloneAng[1], targetAngZ - s.cloneAng[2],
                true);
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
        if (records.length < 15) return;

        int applied = 0;
        for (Session s : SESSIONS.values()) {
            if (s.dest != level) continue;
            if (s.sub.isRemoved()) continue;

            // Pass 1: manifold-point count PER PAIR involving the clone — each record
            // carries its pair's TOTAL force, so the split must be per pair (a global
            // split under-counts one pair and a missing split multiplies the force by
            // the manifold size).
            java.util.Map<Long, Integer> pairPoints = null;
            for (int i = 0; i + 15 <= records.length; i += 15) {
                int idA = (int) records[i];
                int idB = (int) records[i + 1];
                if (idA != s.cloneId && idB != s.cloneId) continue;
                if (pairPoints == null) pairPoints = new java.util.HashMap<>(4);
                pairPoints.merge(((long) idA << 32) | (idB & 0xFFFFFFFFL), 1, Integer::sum);
            }
            if (pairPoints == null) continue;

            // Pass 2: collect candidate impulses.
            double sumImpulse = 0;
            java.util.ArrayList<double[]> impulses = new java.util.ArrayList<>(pairPoints.size() * 4);
            for (int i = 0; i + 15 <= records.length; i += 15) {
                int idA = (int) records[i];
                int idB = (int) records[i + 1];
                boolean cloneIsA = idA == s.cloneId;
                boolean cloneIsB = idB == s.cloneId;
                if (!cloneIsA && !cloneIsB) continue;

                double mag = records[i + 2];
                if (!Double.isFinite(mag) || mag <= 0 || mag > 1e9) continue;

                int points = pairPoints.get(((long) idA << 32) | (idB & 0xFFFFFFFFL));
                int normalBase = cloneIsA ? i + 3 : i + 6;
                int pointBase = cloneIsA ? i + 9 : i + 12;

                double impulse = mag * FEEDBACK_DT * FEEDBACK_SCALE / points
                    * (FEEDBACK_FLIP ? -1.0 : 1.0);
                impulses.add(new double[]{
                    records[pointBase], records[pointBase + 1], records[pointBase + 2],
                    records[normalBase] * impulse, records[normalBase + 1] * impulse,
                    records[normalBase + 2] * impulse});
                sumImpulse += Math.abs(impulse);
            }
            if (impulses.isEmpty()) continue;

            // Per-tick Δv cap: scale ALL impulses down proportionally so the total can't
            // exceed MAX_FEEDBACK_DV on this ship. Saturation = firm resistance, not ejection.
            double mass = Math.max(1.0, s.sub.getMassTracker().getMass());
            double dv = sumImpulse / mass;
            double damp = dv > MAX_FEEDBACK_DV ? MAX_FEEDBACK_DV / dv : 1.0;

            // Body-local point + body-local force on the REAL body: for translation-only
            // portals the clone's local frame IS the real body's local frame, and the
            // native applyForce expects exactly this convention (COM-relative point,
            // force rotated into world by body orientation).
            for (double[] imp : impulses) {
                Rapier3D.applyForce(s.parentSceneId, s.realId,
                    imp[0], imp[1], imp[2],
                    imp[3] * damp, imp[4] * damp, imp[5] * damp, true);
                applied++;
            }
        }

        if (applied > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFeedbackLogMs > 2000) {
                lastFeedbackLogMs = now;
                LOG.info("[IPL-CLONE-FEEDBACK] applied {} contact impulse(s) from {}",
                    applied, level.dimension().location());
            }
        }
    }
}
